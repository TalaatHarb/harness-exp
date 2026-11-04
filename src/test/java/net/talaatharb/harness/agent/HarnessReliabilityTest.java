package net.talaatharb.harness.agent;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import net.talaatharb.harness.approval.ApprovalDecision;
import net.talaatharb.harness.approval.ApprovalService;
import net.talaatharb.harness.config.SettingsStore;
import net.talaatharb.harness.context.ContextBuilder;
import net.talaatharb.harness.context.Contributors;
import net.talaatharb.harness.guardrails.GuardrailService;
import net.talaatharb.harness.guardrails.SecretRedactionGuardrail;
import net.talaatharb.harness.llm.ModelProvider;
import net.talaatharb.harness.llm.ModelProviderException;
import net.talaatharb.harness.llm.ModelProviderRegistry;
import net.talaatharb.harness.llm.ModelReply;
import net.talaatharb.harness.llm.ModelRequest;
import net.talaatharb.harness.llm.ModelSettings;
import net.talaatharb.harness.tools.RiskLevel;
import net.talaatharb.harness.tools.Tool;
import net.talaatharb.harness.tools.ToolRegistry;
import net.talaatharb.harness.tools.ToolResult;
import net.talaatharb.harness.tools.dynamic.DynamicToolRegistry;
import net.talaatharb.harness.tools.mcp.McpToolSource;

class HarnessReliabilityTest {
    @TempDir Path temp;
    final SettingsStore store = new SettingsStore(null);
    final List<ModelRequest> requests = new ArrayList<>();
    final AtomicInteger approvalRequests = new AtomicInteger();

    @FunctionalInterface interface Reply {
        String chat(ModelRequest request, Consumer<String> sink) throws ModelProviderException;
    }

    AgentLoop loop(Reply reply, Set<Tool> enabled) {
        var s = store.copy();
        s.model.provider = "fake";
        store.replace(s);
        ModelProvider provider = new ModelProvider() {
            public String id() { return "fake"; }
            public String displayName() { return "fake"; }
            public boolean ping(ModelSettings s) { return true; }
            public List<String> listModels(ModelSettings s) { return List.of("fake"); }
            public ModelReply chat(ModelSettings s, ModelRequest request, Consumer<String> sink)
                    throws ModelProviderException {
                requests.add(request);
                return new ModelReply(reply.chat(request, sink), null, 0);
            }
        };
        ToolRegistry tools = new ToolRegistry(enabled, McpToolSource.NONE, new DynamicToolRegistry(temp));
        ContextBuilder context = new ContextBuilder(Set.of(new Contributors.SystemPrompt(),
                new Contributors.ReplyInstructions(), new Contributors.ToolCatalog(tools),
                new Contributors.HistoryWindow()));
        return new AgentLoop(store, new ModelProviderRegistry(Set.of(provider)), context, tools,
                new GuardrailService(Set.of(new SecretRedactionGuardrail())),
                new ApprovalService(r -> {
                    approvalRequests.incrementAndGet();
                    return CompletableFuture.completedFuture(ApprovalDecision.DENIED);
                }, store),
                new HookRegistry(Set.of()));
    }

    @Test void repairsContractBeforeAcceptingFinalReply() {
        var s = store.copy();
        s.reply.allowedReplies = "YES|NO";
        store.replace(s);
        AgentLoop loop = loop((r, sink) -> requests.size() == 1 ? "YES, certainly." : "YES", Set.of());
        var result = loop.run("Answer yes", new Conversation(), new AgentListener() {}, new AtomicBoolean());
        assertEquals(AgentRunResult.Status.COMPLETED, result.status());
        assertEquals("YES", result.reply());
        assertEquals(2, requests.size());
    }

    @Test void exhaustedContractIsAnExplicitFailure() {
        var s = store.copy();
        s.reply.allowedReplies = "YES|NO";
        s.reply.maxRepairAttempts = 1;
        store.replace(s);
        var result = loop((r, sink) -> "maybe", Set.of()).run("Answer", new Conversation(),
                new AgentListener() {}, new AtomicBoolean());
        assertEquals(AgentRunResult.Status.ERROR, result.status());
        assertEquals(2, requests.size());
        assertTrue(result.reason().toLowerCase().contains("contract"));
    }

    @Test void cancellationKeepsGuardedPartialAnswerAndDoesNotRetry() {
        AtomicBoolean cancelled = new AtomicBoolean();
        Conversation conversation = new Conversation();
        AgentLoop loop = loop((r, sink) -> {
            sink.accept("A river flows");
            cancelled.set(true);
            throw new ModelProviderException("interrupted");
        }, Set.of());
        var result = loop.run("Write about rivers", conversation, new AgentListener() {}, cancelled);
        assertEquals(AgentRunResult.Status.CANCELLED, result.status());
        assertEquals("A river flows", result.reply());
        assertTrue(conversation.snapshot().stream().anyMatch(m -> m.content().contains("A river flows")));
        assertEquals(1, requests.size());
    }

    @Test void deniedToolCannotBeRetriedInSameRun() {
        AtomicInteger executions = new AtomicInteger();
        Tool tool = new Tool() {
            public String name() { return "remember"; }
            public String description() { return "remember"; }
            public String argumentsHint() { return "{}"; }
            public RiskLevel risk() { return RiskLevel.SENSITIVE; }
            public ToolResult execute(Map<String, Object> args) {
                executions.incrementAndGet();
                return ToolResult.ok("Saved");
            }
        };
        String call = "```tool_call\n{\"name\":\"remember\",\"arguments\":{\"text\":\"green\"}}\n```";
        var result = loop((r, sink) -> requests.size() <= 2 ? call : "I could not save it.", Set.of(tool))
                .run("Remember green", new Conversation(), new AgentListener() {}, new AtomicBoolean());
        assertEquals(AgentRunResult.Status.COMPLETED, result.status());
        assertEquals(0, executions.get());
        assertEquals(1, approvalRequests.get());
    }

    @Test void explicitMemoryRequestCannotSucceedWithoutToolExecution() {
        var s = store.copy();
        s.memory.directRememberRequests = false;
        store.replace(s);
        Tool tool = memoryTool();
        var result = loop((r, sink) -> "I saved your preference.", Set.of(tool))
                .run("Remember that I prefer metric units.", new Conversation(), new AgentListener() {},
                        new AtomicBoolean());
        assertEquals(AgentRunResult.Status.ERROR, result.status());
        assertTrue(result.reason().contains("has not been performed"));
    }

    @Test void explicitDeniedMemoryGetsAuthoritativeFailureRatherThanFalseSuccess() {
        var s = store.copy();
        s.memory.directRememberRequests = false;
        store.replace(s);
        String call = "```tool_call\n{\"name\":\"remember\",\"arguments\":{\"text\":\"green\"}}\n```";
        var result = loop((r, sink) -> requests.size() == 1 ? call : "I saved it!", Set.of(memoryTool()))
                .run("Remember that my favourite colour is green.", new Conversation(), new AgentListener() {},
                        new AtomicBoolean());
        assertEquals("Memory was not saved: the request was denied.", result.reply());
    }

    private Tool memoryTool() {
        return new Tool() {
            public String name() { return "remember"; }
            public String description() { return "remember"; }
            public String argumentsHint() { return "{}"; }
            public RiskLevel risk() { return RiskLevel.SENSITIVE; }
            public ToolResult execute(Map<String, Object> args) { return ToolResult.ok("Saved"); }
        };
    }
}
