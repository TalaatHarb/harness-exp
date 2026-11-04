package net.talaatharb.harness.agent;

import java.util.ArrayList;
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
import net.talaatharb.harness.guardrails.InputLengthGuardrail;
import net.talaatharb.harness.guardrails.SecretRedactionGuardrail;
import net.talaatharb.harness.llm.Attachment;
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
 * When secret redaction is enabled, streaming token callbacks are withheld: only
 * complete, guarded replies are displayed, so credentials spanning chunks cannot leak.
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

    private HarnessSettings attachmentGuardSettings(HarnessSettings s) {
        HarnessSettings copy = store.mapper().convertValue(s, HarnessSettings.class);
        copy.guardrails.disabledGuardrails.add(InputLengthGuardrail.ID);
        return copy;
    }

    public HookRegistry hooks() {        return hooks;
    }

    public AgentRunResult run(String userMessage, Conversation conversation, AgentListener listener,
            AtomicBoolean cancelled) {
        return run(userMessage, List.of(), conversation, listener, cancelled);
    }

    /** Runs the agent for a user message with files attached (see {@link Attachment}). */
    public AgentRunResult run(String userMessage, List<Attachment> attachments, Conversation conversation,
            AgentListener listener, AtomicBoolean cancelled) {
        String previousScope = ConversationScope.enter(conversation.id());
        try {
            return runScoped(userMessage, attachments, conversation, listener, cancelled);
        } finally {
            ConversationScope.restore(previousScope);
        }
    }

    private AgentRunResult runScoped(String userMessage, List<Attachment> attachments, Conversation conversation,
            AgentListener listener, AtomicBoolean cancelled) {
        HarnessSettings settings = store.current();
        AgentRunContext ctx = new AgentRunContext(userMessage, attachments, settings, cancelled);
        AgentRunResult result;
        try {
            result = loop(ctx, conversation, listener);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            result = result(ctx, Status.CANCELLED, null, "Interrupted");
        } catch (RuntimeException e) {
            LOG.error("Agent run {} failed: {}", ctx.runId(), SecretRedactionGuardrail.redact(e.toString(), settings));
            ctx.recordError(e);
            hooks.fire(HookPoint.ON_ERROR, ctx);
            result = result(ctx, Status.ERROR, null, e.getMessage());
        }
        if (result.status() == Status.CANCELLED && !ctx.partialReply().isBlank()) {
            GuardrailResult partial = guardrails.evaluate(GuardrailStage.OUTPUT, ctx.partialReply(), settings);
            if (!partial.blocked() && ToolCallParser.parse(partial.content()).isEmpty()
                    && ToolCallParser.diagnose(partial.content()).isEmpty()) {
                String safe = SecretRedactionGuardrail.redact(partial.content(), settings);
                conversation.add(ChatMessage.assistant(safe + "\n[Response interrupted by user.]"));
                result = result(ctx, Status.CANCELLED, safe, result.reason());
            }
        }
        hooks.fire(HookPoint.AFTER_RUN, ctx);
        result = new AgentRunResult(result.status(), SecretRedactionGuardrail.redact(result.reply(), settings),
                SecretRedactionGuardrail.redact(result.reason(), settings), result.iterations(), result.toolCalls());
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
        List<Attachment> attachments = new ArrayList<>();
        for (Attachment attachment : ctx.attachments()) {
            if (attachment.text() == null) {
                attachments.add(attachment);
                continue;
            }
            // Attachments have their own size limits, so the message length guardrail doesn't apply to them.
            GuardrailResult check = guardrails.evaluate(GuardrailStage.INPUT, attachment.text(),
                    attachmentGuardSettings(s));
            if (check.blocked()) {
                return result(ctx, Status.BLOCKED, null, "Attachment " + attachment.name() + ": " + check.reason());
            }
            if (check.decision() == GuardrailResult.Decision.MODIFY) {
                listener.onNotice(SecretRedactionGuardrail.redact(
                        "Attachment " + attachment.name() + " modified by guardrails: " + check.reason(), s));
                attachment = attachment.withText(check.content());
            }
            attachments.add(attachment);
        }
        conversation.add(SecretRedactionGuardrail.sanitizeMessage(ChatMessage.user(input.content(), attachments), s));

        java.util.regex.Matcher directMemory = java.util.regex.Pattern.compile(
                "(?is)^\\s*(?:please\\s+)?remember\\s+that\\s+([^.!?\\r\\n]+)[.!]?\\s*$").matcher(input.content());
        if ((s.memory == null || s.memory.directRememberRequests) && attachments.isEmpty() && directMemory.matches()
                && tools.findEnabled(net.talaatharb.harness.memory.RememberTool.NAME, s).isPresent()) {
            AgentRunResult stop = checkStop(ctx, limits);
            if (stop != null) return stop;
            ctx.nextIteration();
            ToolCall call = new ToolCall(net.talaatharb.harness.memory.RememberTool.NAME,
                    java.util.Map.of("text", "The user says: " + directMemory.group(1).strip()));
            ctx.recordToolCall(call);
            String recorded = "```tool_call\n" + ToolCallParser.toJson(
                    java.util.Map.of("name", call.name(), "arguments", call.arguments())) + "\n```";
            conversation.add(ChatMessage.assistant(recorded));
            listener.onNotice("Explicit memory request: saving the full fact through the approved tool.");
            listener.onToolCall(safeToolCall(call, s));
            ToolResult raw = executeTool(ctx, call, listener);
            ToolResult outcome = new ToolResult(raw.success(), SecretRedactionGuardrail.redact(raw.output(), s));
            ctx.setLastToolResult(outcome);
            listener.onToolResult(safeToolCall(call, s), outcome);
            hooks.fire(HookPoint.AFTER_TOOL_CALL, ctx);
            conversation.add(ChatMessage.tool(call.name(), formatToolResult(call, outcome, s)));
            stop = checkStop(ctx, limits);
            if (stop != null) return stop;
            String confirmation = outcome.success() ? outcome.output()
                    : ctx.wasDenied(call.name()) ? "Memory was not saved: the request was denied."
                    : "Memory request did not complete successfully: " + outcome.output();
            return finalAnswer(ctx, conversation, confirmation);
        }

        ModelProvider provider;
        try {
            provider = providers.get(s.model);
        } catch (ModelProviderException e) {
            LOG.error("Run {}: no model provider for '{}': {}", ctx.runId(), s.model.provider,
                    SecretRedactionGuardrail.redact(e.toString(), s));
            return result(ctx, Status.ERROR, null, e.getMessage());
        }

        int repairs = 0;
        // An explicit write request must not be satisfied by a generated card or an unsupported success claim.
        String requiredTool = input.content().matches("(?is)^\\s*(?:please\\s+)?remember\\s+that\\s+\\S.*")
                && tools.findEnabled(net.talaatharb.harness.memory.RememberTool.NAME, s).isPresent()
                        ? net.talaatharb.harness.memory.RememberTool.NAME : null;
        while (true) {
            AgentRunResult stop = checkStop(ctx, limits);
            if (stop != null) {
                return stop;
            }
            if (ctx.iteration() >= limits.effectiveMaxIterations()) {
                return limit(ctx, "Reached the maximum of " + limits.effectiveMaxIterations() + " iterations");
            }
            ctx.nextIteration();

            // Guard after all contributors (including tools, memory and RAG), not only the latest user text.
            List<ChatMessage> assembled = new ArrayList<>(contextBuilder.build(s, conversation.snapshot()));
            if (requiredTool != null && !ctx.attempted(requiredTool)) {
                String instruction = "The user explicitly requested a memory write. You must call "
                        + "`remember` with every requested fact before replying. A UI card or an assurance does not "
                        + "save anything. Use the tool_call protocol, not a ui block. Do not omit any requested facts.";
                if (!assembled.isEmpty() && assembled.get(0).role() == net.talaatharb.harness.llm.Role.SYSTEM) {
                    assembled.set(0, ChatMessage.system(assembled.get(0).content() + "\n\n" + instruction));
                } else {
                    assembled.add(0, ChatMessage.system(instruction));
                }
            }
            List<ChatMessage> context = new ArrayList<>(assembled.stream()
                    .map(message -> SecretRedactionGuardrail.sanitizeMessage(message, s)).toList());
            if (SecretRedactionGuardrail.enabled(s) && context.stream()
                    .anyMatch(m -> m.contentForModel().contains(SecretRedactionGuardrail.REDACTED))) {
                String policy = "Sensitive values were intentionally replaced with [REDACTED]. Do not ask the user "
                        + "to provide the original passwords, API keys or tokens. Explain limitations and use "
                        + "non-secret format examples instead.";
                if (!context.isEmpty() && context.get(0).role() == net.talaatharb.harness.llm.Role.SYSTEM) {
                    context.set(0, ChatMessage.system(context.get(0).content() + "\n\n" + policy));
                } else {
                    context.add(0, ChatMessage.system(policy));
                }
            }
            context = List.copyOf(context);
            ctx.setLastContext(context);
            hooks.fire(HookPoint.BEFORE_MODEL_CALL, ctx);
            if (ctx.abortReason() != null) {
                return result(ctx, Status.ABORTED, null, ctx.abortReason());
            }
            listener.onContext(context);

            ModelReply reply;
            ctx.resetStream();
            try {
                // A credential can span arbitrary chunks: never publish unguarded partial tokens.
                reply = provider.chat(s.model, new ModelRequest(s.model.model, context, s.model.streaming),
                        token -> {
                            ctx.appendStream(token);
                            if (!SecretRedactionGuardrail.enabled(s)) {
                                listener.onToken(token);
                            }
                        });
                ctx.clearErrors();
            } catch (ModelProviderException e) {
                AgentRunResult interrupted = checkStop(ctx, limits);
                if (interrupted != null) {
                    return interrupted;
                }
                int errors = ctx.recordError(e);
                LOG.error("Run {}: model call failed (consecutive error {} of {}): {}", ctx.runId(), errors,
                        limits.effectiveMaxConsecutiveErrors(), SecretRedactionGuardrail.redact(e.toString(), s));
                hooks.fire(HookPoint.ON_ERROR, ctx);
                if (errors >= limits.effectiveMaxConsecutiveErrors()) {
                    return result(ctx, Status.ERROR, null, e.getMessage());
                }
                listener.onNotice("Model error (" + errors + "), retrying: "
                        + SecretRedactionGuardrail.redact(e.getMessage(), s));
                continue;
            }
            ctx.setLastReply(reply);
            if (ctx.partialReply().isEmpty()) {
                ctx.appendStream(reply.content());
            }
            if (reply.thinking() != null && !reply.thinking().isBlank()) {
                listener.onThinking(SecretRedactionGuardrail.redact(reply.thinking(), s));
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
                    if (requiredTool != null && ctx.attempted(requiredTool) && !ctx.succeeded(requiredTool)) {
                        String failure = ctx.wasDenied(requiredTool)
                                ? "Memory was not saved: the request was denied."
                                : "Memory request did not complete successfully: " + ctx.lastToolResult().output();
                        return finalAnswer(ctx, conversation, SecretRedactionGuardrail.redact(failure, s));
                    }
                    GuardrailResult guarded = guardrails.evaluate(GuardrailStage.OUTPUT, reply.content(), s);
                    if (guarded.blocked()) {
                        return finalAnswer(ctx, conversation, reply.content());
                    }
                    String safe = SecretRedactionGuardrail.redact(guarded.content(), s);
                    List<String> problems = new ArrayList<>(
                            net.talaatharb.harness.reply.ResponseContract.errors(safe, s.reply));
                    if (requiredTool != null && !ctx.attempted(requiredTool)) {
                        problems.add("The requested action has not been performed. Call `remember` using exactly "
                                + "one tool_call block and include every requested fact; do not claim it was saved.");
                    }
                    problems.addAll(net.talaatharb.harness.reply.ReplyFormat.validate(safe, s.reply.mode));
                    if (problems.isEmpty()) {
                        return finalAnswer(ctx, conversation, safe);
                    }
                    String reason = "Response contract failed: " + String.join(" ", problems);
                    int maxRepairs = Math.max(0, Math.min(3, s.reply.maxRepairAttempts));
                    if (repairs++ >= maxRepairs) {
                        LOG.warn("Run {}: {}", ctx.runId(), reason);
                        return result(ctx, Status.ERROR, null, reason);
                    }
                    conversation.add(ChatMessage.assistant(safe));
                    conversation.add(ChatMessage.tool("response_contract", reason
                            + "\nCorrect the response without repeating tool actions."));
                    listener.onIntermediateReply(safe);
                    listener.onNotice(reason + " Asking the model to correct it (" + repairs + "/" + maxRepairs + ").");
                    ctx.resetStream();
                    continue;
                }
                // Bounded by maxIterations: each correction attempt costs one iteration.
                String safeReply = SecretRedactionGuardrail.redact(reply.content(), s);
                conversation.add(ChatMessage.assistant(safeReply));
                listener.onIntermediateReply(safeReply);
                LOG.warn("Run {}: malformed tool call ({}), asking the model to correct it", ctx.runId(),
                        SecretRedactionGuardrail.redact(malformed.get(), s));
                ctx.recordError(new IllegalArgumentException("Malformed tool call: " + malformed.get()));
                hooks.fire(HookPoint.ON_ERROR, ctx);
                listener.onNotice(SecretRedactionGuardrail.redact(
                        "Malformed tool call (" + malformed.get() + "), asking the model to fix it", s));
                conversation.add(ChatMessage.tool(ToolCallParser.FENCE,
                        SecretRedactionGuardrail.redact(MALFORMED_TOOL_CALL + malformed.get(), s)));
                ctx.resetStream();
                continue;
            }

            ctx.resetStream();
            String safeReply = SecretRedactionGuardrail.redact(reply.content(), s);
            conversation.add(ChatMessage.assistant(safeReply));
            listener.onIntermediateReply(safeReply);
            ToolCall toolCall = call.get();
            if (ctx.toolCalls() >= limits.effectiveMaxToolCalls()) {
                return limit(ctx, "Reached the maximum of " + limits.effectiveMaxToolCalls() + " tool calls");
            }
            int repeats = ctx.recordToolCall(toolCall);
            ToolCall displayCall = safeToolCall(toolCall, s);
            listener.onToolCall(displayCall);
            if (repeats > limits.effectiveMaxIdenticalToolCalls()) {
                return limit(ctx, "Tool call '" + toolCall.name() + "' repeated " + repeats
                        + " times with identical arguments");
            }

            ToolResult rawResult = executeTool(ctx, toolCall, listener);
            ToolResult toolResult = new ToolResult(rawResult.success(),
                    SecretRedactionGuardrail.redact(rawResult.output(), s));
            ctx.setLastToolResult(toolResult);
            listener.onToolResult(displayCall, toolResult);
            hooks.fire(HookPoint.AFTER_TOOL_CALL, ctx);
            conversation.add(ChatMessage.tool(toolCall.name(), formatToolResult(toolCall, toolResult, s)));
        }
    }

    private ToolCall safeToolCall(ToolCall call, HarnessSettings settings) {
        return new ToolCall(SecretRedactionGuardrail.redact(call.name(), settings),
                safeMap(call.arguments(), settings));
    }

    private Object safeValue(Object value, HarnessSettings settings) {
        if (value instanceof String text) {
            return SecretRedactionGuardrail.redact(text, settings);
        }
        if (value instanceof java.util.Map<?, ?> map) {
            return safeMap(map, settings);
        }
        if (value instanceof List<?> list) {
            return list.stream().map(item -> safeValue(item, settings)).toList();
        }
        return value;
    }

    private java.util.Map<String, Object> safeMap(java.util.Map<?, ?> map, HarnessSettings settings) {
        java.util.Map<String, Object> safe = new java.util.LinkedHashMap<>();
        map.forEach((key, item) -> {
            Object sanitized = safeValue(item, settings);
            if (item instanceof String text) {
                String prefix = key + ": ";
                String labeled = SecretRedactionGuardrail.redact(prefix + text, settings);
                if (labeled.startsWith(prefix)) sanitized = labeled.substring(prefix.length());
            }
            safe.put(SecretRedactionGuardrail.redact(key.toString(), settings), sanitized);
        });
        return safe;
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
        if (ctx.wasDenied(call.name())) {
            return ToolResult.error("This tool was already denied in this run. The action was not executed. "
                    + "Do not retry it; tell the user it was not completed.");
        }
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
            ApprovalDecision decision = approvals.await(approvals.requestFor(tool,
                    SecretRedactionGuardrail.redact(argsJson, s), s), s);
            LOG.info("Run {}: approval for '{}' ({}): {}", ctx.runId(), tool.name(), tool.risk(), decision);
            if (!decision.approved()) {
                ctx.deny(call.name());
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
            LOG.error("Run {}: tool '{}' failed: {}", ctx.runId(), call.name(),
                    SecretRedactionGuardrail.redact(e.toString(), s));
            ctx.recordError(e);
            hooks.fire(HookPoint.ON_ERROR, ctx);
            return ToolResult.error("Tool failed: " + e.getMessage());
        }
    }

    static String formatToolResult(ToolCall call, ToolResult result, HarnessSettings s) {
        String output = result.output() == null ? "" : result.output();
        int max = Math.max(100, s.tools.maxToolOutputChars);
        if (output.length() > max && !net.talaatharb.harness.memory.RememberTool.NAME.equals(call.name())) {
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
