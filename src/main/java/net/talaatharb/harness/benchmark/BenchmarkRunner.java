package net.talaatharb.harness.benchmark;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.inject.Guice;
import com.google.inject.Injector;
import com.google.inject.multibindings.Multibinder;

import net.talaatharb.harness.agent.AgentListener;
import net.talaatharb.harness.agent.AgentLoop;
import net.talaatharb.harness.agent.AgentRunResult;
import net.talaatharb.harness.agent.Conversation;
import net.talaatharb.harness.approval.ApprovalDecision;
import net.talaatharb.harness.approval.ApprovalGateway;
import net.talaatharb.harness.approval.ApprovalPolicy;
import net.talaatharb.harness.config.AppModule;
import net.talaatharb.harness.config.HarnessSettings;
import net.talaatharb.harness.config.SettingsStore;
import net.talaatharb.harness.config.StorageSettings;
import net.talaatharb.harness.guardrails.SecretRedactionGuardrail;
import net.talaatharb.harness.llm.ModelProvider;
import net.talaatharb.harness.memory.MemoryService;
import net.talaatharb.harness.rag.RagService;
import net.talaatharb.harness.tools.ToolCall;
import net.talaatharb.harness.tools.ToolResult;
import net.talaatharb.harness.tools.mcp.McpClientManager;

/** The production loop and services, with fresh storage and a deny-by-default approval gateway. */
public final class BenchmarkRunner {
    private static final Set<String> APPROVABLE = Set.of("remember", "create_skill");
    private final ObjectMapper mapper = new SettingsStore(null).mapper();
    private final Set<ModelProvider> testProviders;

    public BenchmarkRunner() {
        this(null);
    }

    public BenchmarkRunner(Set<ModelProvider> testProviders) {
        this.testProviders = testProviders;
    }

    public record TurnResult(String prompt, String reply, String status, int iterations, int toolCalls,
            long durationMillis, List<String> trace, List<String> failures, String review) {
    }

    public record CaseResult(String id, JsonNode effectiveSettings, List<TurnResult> turns) {
        public boolean passed() {
            return turns.stream().allMatch(t -> t.failures().isEmpty());
        }
    }

    public CaseResult run(BenchmarkSuite.Case task, HarnessSettings baseline, Path directory) throws IOException {
        validate(task);
        Files.createDirectory(directory);
        HarnessSettings settings = mapper.convertValue(baseline, HarnessSettings.class);
        if (task.settings != null) {
            ObjectNode tree = mapper.valueToTree(settings);
            merge(tree, task.settings);
            settings = mapper.treeToValue(tree, HarnessSettings.class);
        }
        // Never follow imported paths, launch MCP processes, or compile persisted/generated code.
        settings.storage = new StorageSettings();
        settings.tools.mcpServers.clear();
        settings.tools.disabledTools.addAll(Set.of("create_tool", "read_file", "rag_add"));
        settings.approval.policy = ApprovalPolicy.RISKY_TOOLS;
        settings.approval.alwaysAllowedTools.clear();
        Path settingsFile = directory.resolve("settings.json");
        mapper.writeValue(settingsFile.toFile(), settings);
        Injector injector = Guice.createInjector(new AppModule(settingsFile) {
            @Override protected void bindApprovalGateway() {
                bind(ApprovalGateway.class).toInstance(request -> CompletableFuture.completedFuture(
                        APPROVABLE.contains(request.toolName()) && task.approveTools.contains(request.toolName())
                                ? ApprovalDecision.APPROVED : ApprovalDecision.DENIED));
            }

            @Override protected void bindModelProviders() {
                if (testProviders == null) {
                    super.bindModelProviders();
                } else {
                    Multibinder<ModelProvider> binder = Multibinder.newSetBinder(binder(), ModelProvider.class);
                    testProviders.forEach(p -> binder.addBinding().toInstance(p));
                }
            }
        });
        MemoryService memory = null;
        RagService rag = null;
        McpClientManager mcp = null;
        try {
            memory = injector.getInstance(MemoryService.class);
            rag = injector.getInstance(RagService.class);
            mcp = injector.getInstance(McpClientManager.class);
            AgentLoop loop = injector.getInstance(AgentLoop.class);
            Map<String, Conversation> conversations = new HashMap<>();
            Conversation main = new Conversation("main");
            conversations.put("main", main);
            if (task.document != null) {
                rag.ingestText(main.id(), task.documentName, "benchmark", task.document, null);
            }
            List<TurnResult> results = new ArrayList<>();
            for (BenchmarkSuite.Turn turn : task.turns) {
                List<String> trace = new ArrayList<>();
                List<String> called = new ArrayList<>();
                List<String> contexts = new ArrayList<>();
                Conversation conversation = conversations.computeIfAbsent(turn.conversation, Conversation::new);
                long started = System.nanoTime();
                AgentRunResult result = loop.run(turn.prompt, conversation, new AgentListener() {
                    @Override public void onContext(List<net.talaatharb.harness.llm.ChatMessage> messages) {
                        contexts.add(messages.stream().map(m -> m.contentForModel())
                                .collect(java.util.stream.Collectors.joining("\n")));
                    }
                    @Override public void onToolCall(ToolCall call) {
                        called.add(call.name());
                    }
                    @Override public void onToolResult(ToolCall call, ToolResult result) {
                        trace.add(call.name() + " [" + (result.success() ? "success" : "failed/denied")
                                + "]: " + result.output());
                    }
                    @Override public void onNotice(String notice) {
                        trace.add(notice);
                    }
                }, new AtomicBoolean());
                List<String> failures = check(turn, result, called, memory);
                for (String secret : turn.contextExcludes) {
                    if (contexts.isEmpty() || contexts.stream().anyMatch(c -> c.contains(secret))) {
                        failures.add("Outgoing context was absent or contained forbidden text");
                    }
                }
                results.add(new TurnResult(turn.prompt, result.reply(), result.status().name(), result.iterations(),
                        result.toolCalls(), (System.nanoTime() - started) / 1_000_000, trace, failures, turn.review));
            }
            ObjectNode snapshot = mapper.valueToTree(injector.getInstance(SettingsStore.class).current());
            ((ObjectNode) snapshot.get("model")).remove("apiKey");
            return new CaseResult(task.id, snapshot, results);
        } finally {
            if (mcp != null) mcp.close();
            if (rag != null) rag.close();
            if (memory != null) memory.close();
        }
    }

    static List<String> check(BenchmarkSuite.Turn turn, AgentRunResult result, List<String> called,
            MemoryService memory) {
        List<String> failures = new ArrayList<>();
        String text = result.reply() == null ? "" : result.reply();
        String lower = text.toLowerCase(Locale.ROOT);
        if (!result.status().name().equals(turn.status)) failures.add("Expected status " + turn.status);
        if (turn.exact != null && !turn.exact.equals(text.strip())) failures.add("Exact reply mismatch");
        for (String expected : turn.contains) {
            if (!lower.contains(expected.toLowerCase(Locale.ROOT))) failures.add("Missing reply text: " + expected);
        }
        for (String forbidden : turn.excludes) {
            if (lower.contains(forbidden.toLowerCase(Locale.ROOT))) failures.add("Forbidden reply text: " + forbidden);
        }
        for (String tool : turn.tools) {
            if (!called.contains(tool)) failures.add("Tool not called: " + tool);
        }
        String stored = memory.active().list().stream().map(e -> e.text.toLowerCase(Locale.ROOT))
                .collect(java.util.stream.Collectors.joining("\n"));
        for (String fact : turn.savedMemories) {
            if (!stored.contains(fact.toLowerCase(Locale.ROOT))) failures.add("Memory not saved: " + fact);
        }
        if (turn.memoryCount != null && memory.active().list().size() != turn.memoryCount) {
            failures.add("Expected " + turn.memoryCount + " saved memories");
        }
        return failures;
    }

    static void validate(BenchmarkSuite.Case task) {
        if (task.id == null || task.id.isBlank() || task.turns == null || task.turns.isEmpty()) {
            throw new IllegalArgumentException("Each case needs an id and at least one turn");
        }
        if (!APPROVABLE.containsAll(task.approveTools)) {
            throw new IllegalArgumentException("Only remember and create_skill may be auto-approved");
        }
        for (BenchmarkSuite.Turn turn : task.turns) {
            if (turn.prompt == null || turn.prompt.isBlank() || turn.conversation == null
                    || turn.conversation.isBlank()) {
                throw new IllegalArgumentException("Each turn needs a prompt and conversation");
            }
            AgentRunResult.Status.valueOf(turn.status);
        }
    }

    private static void merge(ObjectNode target, JsonNode patch) {
        if (!patch.isObject()) throw new IllegalArgumentException("Settings override must be a JSON object");
        patch.fields().forEachRemaining(e -> {
            JsonNode old = target.get(e.getKey());
            if (old instanceof ObjectNode object && e.getValue().isObject()) {
                merge(object, e.getValue());
            } else {
                target.set(e.getKey(), e.getValue());
            }
        });
    }
}
