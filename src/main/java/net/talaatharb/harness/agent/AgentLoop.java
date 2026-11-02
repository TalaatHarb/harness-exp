package net.talaatharb.harness.agent;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.inject.Inject;
import com.google.inject.Singleton;

import net.talaatharb.harness.agent.AgentRunResult.Status;
import net.talaatharb.harness.approval.ApprovalDecision;
import net.talaatharb.harness.approval.ApprovalService;
import net.talaatharb.harness.config.HarnessSettings;
import net.talaatharb.harness.config.SettingsStore;
import net.talaatharb.harness.context.ContextBuilder;
import net.talaatharb.harness.guardrails.GuardrailResult;
import net.talaatharb.harness.guardrails.GuardrailService;
import net.talaatharb.harness.guardrails.GuardrailStage;
import net.talaatharb.harness.llm.ChatMessage;
import net.talaatharb.harness.llm.ModelProvider;
import net.talaatharb.harness.llm.ModelProviderException;
import net.talaatharb.harness.llm.ModelProviderRegistry;
import net.talaatharb.harness.llm.ModelReply;
import net.talaatharb.harness.llm.ModelRequest;
import net.talaatharb.harness.tools.Tool;
import net.talaatharb.harness.tools.ToolCall;
import net.talaatharb.harness.tools.ToolCallParser;
import net.talaatharb.harness.tools.ToolRegistry;
import net.talaatharb.harness.tools.ToolResult;

/**
 * ReAct-style loop: build context → call model → (optional) guarded, approved
 * tool call → feed result back → repeat until the model answers or a limit
 * from {@link AgentLoopLimits} trips. Hooks fire at every {@link HookPoint}.
 */
@Singleton
public class AgentLoop {
    private static final Logger LOG = LoggerFactory.getLogger(AgentLoop.class);
    static final String MALFORMED_TOOL_CALL = "Error: your tool_call block could not be used. Reply with exactly one "
            + "```tool_call block containing a single JSON object {\"name\": \"...\", \"arguments\": {...}}; "
            + "escape quotes (\\\") and newlines (\\n) inside JSON strings. Problem: ";

    private final SettingsStore store;
    private final ModelProviderRegistry providers;
    private final ContextBuilder contextBuilder;
    private final ToolRegistry tools;
    private final GuardrailService guardrails;
    private final ApprovalService approvals;
    private final HookRegistry hooks;

    @Inject
    public AgentLoop(SettingsStore store, ModelProviderRegistry providers, ContextBuilder contextBuilder,
            ToolRegistry tools, GuardrailService guardrails, ApprovalService approvals, HookRegistry hooks) {
        this.store = store;
        this.providers = providers;
        this.contextBuilder = contextBuilder;
        this.tools = tools;
        this.guardrails = guardrails;
        this.approvals = approvals;
        this.hooks = hooks;
    }

    public HookRegistry hooks() {
        return hooks;
    }

    public AgentRunResult run(String userMessage, Conversation conversation, AgentListener listener,
            AtomicBoolean cancelled) {
        HarnessSettings settings = store.current();
        AgentRunContext ctx = new AgentRunContext(userMessage, settings, cancelled);
        AgentRunResult result;
        try {
            result = loop(ctx, conversation, listener);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            result = result(ctx, Status.CANCELLED, null, "Interrupted");
        } catch (RuntimeException e) {
            LOG.error("Agent run {} failed", ctx.runId(), e);
            ctx.recordError(e);
            hooks.fire(HookPoint.ON_ERROR, ctx);
            result = result(ctx, Status.ERROR, null, e.getMessage());
        }
        hooks.fire(HookPoint.AFTER_RUN, ctx);
        if (result.status() == Status.ERROR) {
            LOG.error("Agent run {} ended with error: {}", ctx.runId(), result.reason());
        } else if (result.status() != Status.COMPLETED) {
            LOG.warn("Agent run {} ended with {}: {}", ctx.runId(), result.status(), result.reason());
        }
        listener.onFinished(result);
        return result;
    }

    private AgentRunResult loop(AgentRunContext ctx, Conversation conversation, AgentListener listener)
            throws InterruptedException {
        HarnessSettings s = ctx.settings();
        AgentLoopSettings limits = s.agentLoop;

        hooks.fire(HookPoint.BEFORE_RUN, ctx);
        if (ctx.abortReason() != null) {
            return result(ctx, Status.ABORTED, null, ctx.abortReason());
        }
        GuardrailResult input = guardrails.evaluate(GuardrailStage.INPUT, ctx.userMessage(), s);
        if (input.blocked()) {
            return result(ctx, Status.BLOCKED, null, input.reason());
        }
        if (input.decision() == GuardrailResult.Decision.MODIFY) {
            listener.onNotice("Input modified by guardrails: " + input.reason());
        }
        conversation.add(ChatMessage.user(input.content()));

        ModelProvider provider;
        try {
            provider = providers.get(s.model);
        } catch (ModelProviderException e) {
            LOG.error("Run {}: no model provider for '{}'", ctx.runId(), s.model.provider, e);
            return result(ctx, Status.ERROR, null, e.getMessage());
        }

        while (true) {
            AgentRunResult stop = checkStop(ctx, limits);
            if (stop != null) {
                return stop;
            }
            if (ctx.iteration() >= limits.effectiveMaxIterations()) {
                return limit(ctx, "Reached the maximum of " + limits.effectiveMaxIterations() + " iterations");
            }
            ctx.nextIteration();

            List<ChatMessage> context = contextBuilder.build(s, conversation.snapshot());
            ctx.setLastContext(context);
            hooks.fire(HookPoint.BEFORE_MODEL_CALL, ctx);
            if (ctx.abortReason() != null) {
                return result(ctx, Status.ABORTED, null, ctx.abortReason());
            }

            ModelReply reply;
            try {
                reply = provider.chat(s.model, new ModelRequest(s.model.model, context, s.model.streaming), listener::onToken);
                ctx.clearErrors();
            } catch (ModelProviderException e) {
                int errors = ctx.recordError(e);
                LOG.error("Run {}: model call failed (consecutive error {} of {})", ctx.runId(), errors,
                        limits.effectiveMaxConsecutiveErrors(), e);
                hooks.fire(HookPoint.ON_ERROR, ctx);
                if (errors >= limits.effectiveMaxConsecutiveErrors()) {
                    return result(ctx, Status.ERROR, null, e.getMessage());
                }
                listener.onNotice("Model error (" + errors + "), retrying: " + e.getMessage());
                continue;
            }
            ctx.setLastReply(reply);
            if (reply.thinking() != null && !reply.thinking().isBlank()) {
                listener.onThinking(reply.thinking());
            }
            hooks.fire(HookPoint.AFTER_MODEL_CALL, ctx);
            stop = checkStop(ctx, limits);
            if (stop != null) {
                return stop;
            }

            Optional<ToolCall> call = ToolCallParser.parse(reply.content());
            if (call.isEmpty()) {
                Optional<String> malformed = ToolCallParser.diagnose(reply.content());
                if (malformed.isEmpty()) {
                    return finalAnswer(ctx, conversation, reply.content());
                }
                // Bounded by maxIterations: each correction attempt costs one iteration.
                conversation.add(ChatMessage.assistant(reply.content()));
                listener.onIntermediateReply(reply.content());
                LOG.warn("Run {}: malformed tool call ({}), asking the model to correct it", ctx.runId(),
                        malformed.get());
                ctx.recordError(new IllegalArgumentException("Malformed tool call: " + malformed.get()));
                hooks.fire(HookPoint.ON_ERROR, ctx);
                listener.onNotice("Malformed tool call (" + malformed.get() + "), asking the model to fix it");
                conversation.add(ChatMessage.tool(ToolCallParser.FENCE, MALFORMED_TOOL_CALL + malformed.get()));
                continue;
            }

            conversation.add(ChatMessage.assistant(reply.content()));
            listener.onIntermediateReply(reply.content());
            ToolCall toolCall = call.get();
            if (ctx.toolCalls() >= limits.effectiveMaxToolCalls()) {
                return limit(ctx, "Reached the maximum of " + limits.effectiveMaxToolCalls() + " tool calls");
            }
            int repeats = ctx.recordToolCall(toolCall);
            listener.onToolCall(toolCall);
            if (repeats > limits.effectiveMaxIdenticalToolCalls()) {
                return limit(ctx, "Tool call '" + toolCall.name() + "' repeated " + repeats
                        + " times with identical arguments");
            }

            ToolResult toolResult = executeTool(ctx, toolCall, listener);
            ctx.setLastToolResult(toolResult);
            listener.onToolResult(toolCall, toolResult);
            hooks.fire(HookPoint.AFTER_TOOL_CALL, ctx);
            conversation.add(ChatMessage.tool(toolCall.name(), formatToolResult(toolCall, toolResult, s)));
        }
    }

    private AgentRunResult finalAnswer(AgentRunContext ctx, Conversation conversation, String content) {
        GuardrailResult output = guardrails.evaluate(GuardrailStage.OUTPUT, content, ctx.settings());
        if (output.blocked()) {
            conversation.add(ChatMessage.assistant("[response withheld by guardrails]"));
            return result(ctx, Status.BLOCKED, null, output.reason());
        }
        conversation.add(ChatMessage.assistant(output.content()));
        return result(ctx, Status.COMPLETED, output.content(), output.reason());
    }

    private ToolResult executeTool(AgentRunContext ctx, ToolCall call, AgentListener listener)
            throws InterruptedException {
        HarnessSettings s = ctx.settings();
        Optional<Tool> found = tools.findEnabled(call.name(), s);
        if (found.isEmpty()) {
            String available = tools.enabled(s).stream().map(Tool::name).collect(Collectors.joining(", "));
            return ToolResult.error("Unknown or disabled tool '" + call.name() + "'. Available: " + available);
        }
        Tool tool = found.get();
        String argsJson = ToolCallParser.toJson(call.arguments());
        GuardrailResult guard = guardrails.evaluate(GuardrailStage.TOOL_CALL, call.name() + " " + argsJson, s);
        if (guard.blocked()) {
            LOG.warn("Run {}: tool call '{}' blocked by guardrails: {}", ctx.runId(), call.name(), guard.reason());
            return ToolResult.error("Tool call blocked by guardrails: " + guard.reason());
        }
        if (approvals.requiresApproval(tool, s)) {
            listener.onNotice("Waiting for approval to run '" + tool.name() + "'…");
            ApprovalDecision decision = approvals.await(approvals.requestFor(tool, argsJson, s), s);
            LOG.info("Run {}: approval for '{}' ({}): {}", ctx.runId(), tool.name(), tool.risk(), decision);
            if (!decision.approved()) {
                listener.onNotice("'" + tool.name() + "' was not approved");
                return ToolResult.error("The user denied this tool call. Do not retry it; continue without it.");
            }
        }
        hooks.fire(HookPoint.BEFORE_TOOL_CALL, ctx);
        if (ctx.abortReason() != null) {
            return ToolResult.error("Tool call aborted: " + ctx.abortReason());
        }
        try {
            ToolResult result = tool.execute(call.arguments());
            return result == null ? ToolResult.error("Tool returned no result") : result;
        } catch (InterruptedException e) {
            throw e;
        } catch (Exception e) {
            LOG.error("Run {}: tool '{}' failed", ctx.runId(), call.name(), e);
            ctx.recordError(e);
            hooks.fire(HookPoint.ON_ERROR, ctx);
            return ToolResult.error("Tool failed: " + e.getMessage());
        }
    }

    static String formatToolResult(ToolCall call, ToolResult result, HarnessSettings s) {
        String output = result.output() == null ? "" : result.output();
        int max = Math.max(100, s.tools.maxToolOutputChars);
        if (output.length() > max) {
            output = output.substring(0, max) + "\n[truncated " + (output.length() - max) + " chars]";
        }
        return "Result of tool `" + call.name() + "` (" + (result.success() ? "success" : "error") + "):\n" + output;
    }

    private AgentRunResult checkStop(AgentRunContext ctx, AgentLoopSettings limits) {
        if (ctx.cancelled() || Thread.currentThread().isInterrupted()) {
            return result(ctx, Status.CANCELLED, null, "Cancelled by user");
        }
        if (ctx.abortReason() != null) {
            return result(ctx, Status.ABORTED, null, ctx.abortReason());
        }
        if (ctx.elapsed().compareTo(limits.effectiveMaxDuration()) > 0) {
            return limit(ctx, "Exceeded the time budget of " + limits.effectiveMaxDuration().toSeconds() + "s");
        }
        return null;
    }

    private AgentRunResult limit(AgentRunContext ctx, String reason) {
        ctx.attributes().put("limitReason", reason);
        hooks.fire(HookPoint.ON_LIMIT_REACHED, ctx);
        return result(ctx, Status.LIMIT_REACHED, null, reason);
    }

    private static AgentRunResult result(AgentRunContext ctx, Status status, String reply, String reason) {
        return new AgentRunResult(status, reply, reason, ctx.iteration(), ctx.toolCalls());
    }
}
