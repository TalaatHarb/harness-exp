package net.talaatharb.harness.agent;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import net.talaatharb.harness.approval.ApprovalDecision;
import net.talaatharb.harness.approval.ApprovalPolicy;
import net.talaatharb.harness.approval.ApprovalService;
import net.talaatharb.harness.config.SettingsStore;
import net.talaatharb.harness.context.ContextAssembly;
import net.talaatharb.harness.context.ContextBuilder;
import net.talaatharb.harness.context.ContextContributor;
import net.talaatharb.harness.context.Contributors;
import net.talaatharb.harness.guardrails.GuardrailService;
import net.talaatharb.harness.guardrails.SecretRedactionGuardrail;
import net.talaatharb.harness.llm.*;
import net.talaatharb.harness.tools.*;
import net.talaatharb.harness.tools.mcp.McpToolSource;
import net.talaatharb.harness.tools.dynamic.DynamicToolRegistry;

class AgentLoopRedactionTest {
    private static final String SECRET = "sk-" + "a".repeat(24);
    private final SettingsStore store = new SettingsStore(null);
    private final List<String> display = new ArrayList<>();
    private final List<String> tokens = new ArrayList<>();
    private final List<ModelRequest> requests = new ArrayList<>();
    private String executedArgument;
    private String approvedArguments;
    @TempDir
    java.nio.file.Path dir;

    private AgentLoop loop(boolean tools) {
        store.current().model.provider = "fake";
        Tool tool = new Tool() {
            public String name() { return "secret_source"; }
            public String description() { return "test tool"; }
            public String argumentsHint() { return "{}"; }
            public ToolResult execute(Map<String, Object> args) {
                executedArgument = (String) args.get("value");
                return ToolResult.ok(SECRET);
            }
        };
        ToolRegistry registry = new ToolRegistry(Set.of(tool), McpToolSource.NONE, new DynamicToolRegistry(dir));
        ContextContributor injected = new ContextContributor() {
            public String id() { return "memory-and-rag"; }
            public String description() { return "simulates contributors with sensitive retrieved text"; }
            public int order() { return 50; }
            public void contribute(ContextAssembly assembly) {
                assembly.addSystemSection("Memory: " + SECRET + "\nRAG: " + SECRET);
            }
        };
        ModelProvider provider = new ModelProvider() {
            public String id() { return "fake"; }
            public String displayName() { return "fake"; }
            public boolean ping(ModelSettings settings) { return true; }
            public List<String> listModels(ModelSettings settings) { return List.of("fake"); }
            public ModelReply chat(ModelSettings settings, ModelRequest request, Consumer<String> sink) {
                requests.add(request);
                sink.accept("sk-");
                sink.accept("a".repeat(24));
                String reply = tools && requests.size() == 1
                        ? "```tool_call\n{\"name\":\"secret_source\",\"arguments\":{\"value\":\"" + SECRET + "\"}}\n```"
                        : "Answer " + SECRET;
                return new ModelReply(reply, "Thinking " + SECRET, 1);
            }
        };
        return new AgentLoop(store, new ModelProviderRegistry(Set.of(provider)),
                new ContextBuilder(Set.of(injected, new Contributors.HistoryWindow())), registry,
                new GuardrailService(Set.of(new SecretRedactionGuardrail())),
                new ApprovalService(request -> {
                    approvedArguments = request.argumentsJson();
                    return CompletableFuture.completedFuture(ApprovalDecision.APPROVED);
                }, store),
                new HookRegistry(Set.of()));
    }

    private AgentRunResult run(AgentLoop loop, Conversation conversation) {
        return loop.run("User " + SECRET, List.of(
                new Attachment(SECRET, "text/plain", Attachment.Kind.TEXT, 30, SECRET, null, false)),
                conversation, new AgentListener() {
            public void onToken(String token) { tokens.add(token); }
            public void onThinking(String thinking) { display.add(thinking); }
            public void onIntermediateReply(String reply) { display.add(reply); }
            public void onToolCall(ToolCall call) { display.add(call.toString()); }
            public void onToolResult(ToolCall call, ToolResult result) { display.add(result.output()); }
            public void onNotice(String notice) { display.add(notice); }
            public void onFinished(AgentRunResult result) { display.add(result.reply()); }
        }, new AtomicBoolean());
    }

    @Test
    void protectsAllContextContributorsToolResultsAndDisplayWithoutAlteringToolExecution() {
        AgentLoop loop = loop(true);
        store.current().approval.policy = ApprovalPolicy.ALL_TOOLS;
        Conversation conversation = new Conversation();
        conversation.add(ChatMessage.user("Old history " + SECRET));
        AgentRunResult result = run(loop, conversation);
        assertEquals(AgentRunResult.Status.COMPLETED, result.status());
        assertEquals("Answer [REDACTED]", result.reply());
        assertEquals(SECRET, executedArgument, "redaction must not silently change tool behavior");
        assertFalse(approvedArguments.contains(SECRET), "approval preview must be sanitized too");
        assertEquals(2, requests.size());
        assertTrue(requests.get(0).messages().get(0).content().contains("Memory: [REDACTED]"));
        assertTrue(requests.get(1).messages().stream().anyMatch(m -> m.role() == Role.TOOL));
        assertTrue(requests.stream().flatMap(r -> r.messages().stream())
                .noneMatch(message -> message.contentForModel().contains(SECRET)));
        assertTrue(tokens.isEmpty(), "split credentials must not leak via token callbacks");
        assertFalse(display.isEmpty());
        assertTrue(display.stream().noneMatch(text -> text != null && text.contains(SECRET)));
    }

    @Test
    void disablingSecretGuardrailPreservesStreamingAndContentBehavior() {
        AgentLoop loop = loop(false);
        store.current().guardrails.disabledGuardrails.add(SecretRedactionGuardrail.ID);
        assertEquals("Answer " + SECRET, run(loop, new Conversation()).reply());
        assertEquals(SECRET, String.join("", tokens));
        assertTrue(requests.get(0).messages().stream().anyMatch(message -> message.content().contains(SECRET)));
    }
}
