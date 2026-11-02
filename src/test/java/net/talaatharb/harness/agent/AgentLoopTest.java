package net.talaatharb.harness.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Function;

import org.junit.jupiter.api.Test;

import net.talaatharb.harness.approval.ApprovalDecision;
import net.talaatharb.harness.approval.ApprovalService;
import net.talaatharb.harness.config.HarnessSettings;
import net.talaatharb.harness.config.SettingsStore;
import net.talaatharb.harness.context.ContextBuilder;
import net.talaatharb.harness.context.Contributors;
import net.talaatharb.harness.guardrails.BlockedTermsGuardrail;
import net.talaatharb.harness.guardrails.GuardrailService;
import net.talaatharb.harness.guardrails.InputLengthGuardrail;
import net.talaatharb.harness.guardrails.SecretRedactionGuardrail;
import net.talaatharb.harness.llm.ChatMessage;
import net.talaatharb.harness.llm.ModelProvider;
import net.talaatharb.harness.llm.ModelProviderRegistry;
import net.talaatharb.harness.llm.ModelReply;
import net.talaatharb.harness.llm.ModelRequest;
import net.talaatharb.harness.llm.ModelSettings;
import net.talaatharb.harness.llm.Role;
import net.talaatharb.harness.tools.ToolRegistry;
import net.talaatharb.harness.tools.builtin.CurrentTimeTool;
import net.talaatharb.harness.tools.builtin.ReadFileTool;
import net.talaatharb.harness.tools.mcp.UnconnectedMcpToolSource;

class AgentLoopTest {

    /** Fake provider whose reply is computed from the request (e.g. scripted or based on iteration). */
    static final class ScriptedProvider implements ModelProvider {
        final Function<ModelRequest, String> script;
        final List<ModelRequest> requests = new ArrayList<>();

        ScriptedProvider(Function<ModelRequest, String> script) {
            this.script = script;
        }

        static ScriptedProvider of(String... replies) {
            Iterator<String> it = List.of(replies).iterator();
            return new ScriptedProvider(r -> it.hasNext() ? it.next() : "done");
        }

        @Override
        public String id() {
            return "fake";
        }

        @Override
        public String displayName() {
            return "Fake";
        }

        @Override
        public boolean ping(ModelSettings config) {
            return true;
        }

        @Override
        public List<String> listModels(ModelSettings config) {
            return List.of("fake-model");
        }

        @Override
        public ModelReply chat(ModelSettings config, ModelRequest request, Consumer<String> tokenSink) {
            requests.add(request);
            return new ModelReply(script.apply(request), null, 1);
        }
    }

    private static final String TIME_CALL = "```tool_call\n{\"name\":\"current_time\",\"arguments\":{}}\n```";

    private SettingsStore store;
    private HookRegistry hooks;
    private ApprovalDecision approvalAnswer = ApprovalDecision.APPROVED;
    private int approvalRequests;

    private AgentLoop loop(ScriptedProvider provider) {
        store = new SettingsStore(null);
        HarnessSettings s = store.copy();
        s.model.provider = "fake";
        store.replace(s);
        ToolRegistry tools = new ToolRegistry(Set.of(new CurrentTimeTool(), new ReadFileTool()),
                new UnconnectedMcpToolSource());
        ContextBuilder context = new ContextBuilder(Set.of(new Contributors.SystemPrompt(), new Contributors.Skills(),
                new Contributors.ToolCatalog(tools), new Contributors.HistoryWindow()));
        GuardrailService guardrails = new GuardrailService(Set.of(new InputLengthGuardrail(),
                new BlockedTermsGuardrail(), new SecretRedactionGuardrail()));
        ApprovalService approvals = new ApprovalService(req -> {
            approvalRequests++;
            return CompletableFuture.completedFuture(approvalAnswer);
        }, store);
        hooks = new HookRegistry(Set.of());
        return new AgentLoop(store, new ModelProviderRegistry(Set.of(provider)), context, tools, guardrails,
                approvals, hooks);
    }

    private void update(Consumer<HarnessSettings> change) {
        HarnessSettings s = store.copy();
        change.accept(s);
        store.replace(s);
    }

    private static AgentRunResult run(AgentLoop loop, String message, Conversation conversation) {
        return loop.run(message, conversation, new AgentListener() {
        }, new AtomicBoolean());
    }

    @Test
    void executesToolThenAnswers() {
        ScriptedProvider provider = ScriptedProvider.of(TIME_CALL, "It is noon.");
        AgentLoop loop = loop(provider);
        Conversation conversation = new Conversation();

        AgentRunResult result = run(loop, "What time is it?", conversation);

        assertEquals(AgentRunResult.Status.COMPLETED, result.status());
        assertEquals("It is noon.", result.reply());
        assertEquals(1, result.toolCalls());
        List<Role> roles = conversation.snapshot().stream().map(ChatMessage::role).toList();
        assertEquals(List.of(Role.USER, Role.ASSISTANT, Role.TOOL, Role.ASSISTANT), roles);
        ModelRequest second = provider.requests.get(1);
        assertEquals(Role.SYSTEM, second.messages().get(0).role());
        assertTrue(second.messages().get(0).content().contains("current_time"));
        assertTrue(second.messages().get(second.messages().size() - 1).content().contains("success"));
    }

    @Test
    void stopsRepeatedIdenticalToolCalls() {
        AgentLoop loop = loop(new ScriptedProvider(r -> TIME_CALL));
        AgentRunResult result = run(loop, "loop forever", new Conversation());

        assertEquals(AgentRunResult.Status.LIMIT_REACHED, result.status());
        assertTrue(result.reason().contains("identical"));
        assertEquals(AgentLoopLimits.DEFAULT_MAX_IDENTICAL_TOOL_CALLS + 1, result.toolCalls());
    }

    @Test
    void stopsAtMaxIterationsAndClampsToHardLimit() {
        int[] n = {0};
        AgentLoop loop = loop(new ScriptedProvider(
                r -> "```tool_call\n{\"name\":\"current_time\",\"arguments\":{\"n\":" + (n[0]++) + "}}\n```"));
        update(s -> {
            s.agentLoop.maxIterations = 1_000;
            s.agentLoop.maxToolCalls = 1_000;
        });

        AgentRunResult result = run(loop, "go", new Conversation());

        assertEquals(AgentRunResult.Status.LIMIT_REACHED, result.status());
        assertTrue(result.iterations() <= AgentLoopLimits.HARD_MAX_ITERATIONS);
    }

    @Test
    void inputGuardrailBlocksBeforeModelCall() {
        ScriptedProvider provider = ScriptedProvider.of("never");
        AgentLoop loop = loop(provider);
        update(s -> s.guardrails.blockedTerms.add("forbidden"));

        AgentRunResult result = run(loop, "this is FORBIDDEN text", new Conversation());

        assertEquals(AgentRunResult.Status.BLOCKED, result.status());
        assertTrue(provider.requests.isEmpty());
    }

    @Test
    void outputGuardrailRedactsSecrets() {
        AgentLoop loop = loop(ScriptedProvider.of("key: sk-abcdefghijklmnopqrstuvwxyz"));
        AgentRunResult result = run(loop, "leak", new Conversation());

        assertEquals(AgentRunResult.Status.COMPLETED, result.status());
        assertFalse(result.reply().contains("sk-abc"));
    }

    @Test
    void deniedApprovalIsReportedToModel() {
        ScriptedProvider provider = ScriptedProvider.of(
                "```tool_call\n{\"name\":\"read_file\",\"arguments\":{\"path\":\"pom.xml\"}}\n```", "ok");
        AgentLoop loop = loop(provider);
        approvalAnswer = ApprovalDecision.DENIED;
        Conversation conversation = new Conversation();

        AgentRunResult result = run(loop, "read the pom", conversation);

        assertEquals(AgentRunResult.Status.COMPLETED, result.status());
        assertEquals(1, approvalRequests);
        ChatMessage toolMessage = conversation.snapshot().get(2);
        assertEquals(Role.TOOL, toolMessage.role());
        assertTrue(toolMessage.content().contains("denied"));
    }

    @Test
    void safeToolsSkipApprovalUnderDefaultPolicy() {
        AgentLoop loop = loop(ScriptedProvider.of(TIME_CALL, "done"));
        run(loop, "time?", new Conversation());
        assertEquals(0, approvalRequests);
    }

    @Test
    void hookCanAbortAndAllPointsFire() {
        AgentLoop loop = loop(ScriptedProvider.of("hi"));
        List<HookPoint> seen = new ArrayList<>();
        hooks.register((point, ctx) -> {
            seen.add(point);
            if (point == HookPoint.BEFORE_MODEL_CALL) {
                ctx.abort("test abort");
            }
        });

        AgentRunResult result = run(loop, "hello", new Conversation());

        assertEquals(AgentRunResult.Status.ABORTED, result.status());
        assertEquals(List.of(HookPoint.BEFORE_RUN, HookPoint.BEFORE_MODEL_CALL, HookPoint.AFTER_RUN), seen);
    }

    @Test
    void cancellationStopsRun() {
        AgentLoop loop = loop(ScriptedProvider.of("hi"));
        AtomicBoolean cancelled = new AtomicBoolean(true);
        AgentRunResult result = loop.run("hello", new Conversation(), new AgentListener() {
        }, cancelled);
        assertEquals(AgentRunResult.Status.CANCELLED, result.status());
    }
}
