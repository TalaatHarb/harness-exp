package net.talaatharb.harness.trace;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import net.talaatharb.harness.agent.AgentListener;
import net.talaatharb.harness.agent.AgentLoop;
import net.talaatharb.harness.agent.Conversation;
import net.talaatharb.harness.agent.HookRegistry;
import net.talaatharb.harness.approval.ApprovalDecision;
import net.talaatharb.harness.approval.ApprovalService;
import net.talaatharb.harness.config.HarnessSettings;
import net.talaatharb.harness.config.SettingsStore;
import net.talaatharb.harness.context.ContextBuild;
import net.talaatharb.harness.context.ContextBuilder;
import net.talaatharb.harness.context.Contributors;
import net.talaatharb.harness.guardrails.GuardrailService;
import net.talaatharb.harness.guardrails.SecretRedactionGuardrail;
import net.talaatharb.harness.history.ConversationRecord;
import net.talaatharb.harness.history.ConversationStore;
import net.talaatharb.harness.history.TurnRecord;
import net.talaatharb.harness.llm.ChatMessage;
import net.talaatharb.harness.llm.ModelProvider;
import net.talaatharb.harness.llm.ModelProviderRegistry;
import net.talaatharb.harness.llm.ModelReply;
import net.talaatharb.harness.llm.ModelRequest;
import net.talaatharb.harness.llm.ModelSettings;
import net.talaatharb.harness.tools.ToolRegistry;
import net.talaatharb.harness.tools.builtin.CurrentTimeTool;
import net.talaatharb.harness.tools.dynamic.DynamicToolRegistry;
import net.talaatharb.harness.tools.mcp.McpToolSource;

class RunTimelineTest {
    private static final String SECRET = "sk-" + "c".repeat(24);
    private static final String TIME_CALL = "```tool_call\n{\"name\":\"current_time\",\"arguments\":{}}\n```";

    @TempDir
    Path temp;

    /** Replies with a tool call first, then an answer; reports token usage only for the first call. */
    private static final class UsageProvider implements ModelProvider {
        int calls;

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
            return List.of();
        }

        @Override
        public ModelReply chat(ModelSettings config, ModelRequest request, Consumer<String> sink) {
            return ++calls == 1 ? new ModelReply(TIME_CALL, null, 5, 120, 15)
                    : new ModelReply("It is now. " + SECRET, null, 5);
        }
    }

    @Test
    void agentRunRecordsContextModelToolAndEndEventsWithRedaction() {
        SettingsStore store = new SettingsStore(null);
        HarnessSettings s = store.copy();
        s.model.provider = "fake";
        store.replace(s);
        ToolRegistry tools = new ToolRegistry(Set.of(new CurrentTimeTool()), McpToolSource.NONE,
                new DynamicToolRegistry(temp));
        ContextBuilder context = new ContextBuilder(Set.of(new Contributors.SystemPrompt(),
                new Contributors.ToolCatalog(tools), new Contributors.HistoryWindow()));
        AgentLoop loop = new AgentLoop(store, new ModelProviderRegistry(Set.of(new UsageProvider())), context,
                tools, new GuardrailService(Set.of(new SecretRedactionGuardrail())),
                new ApprovalService(r -> CompletableFuture.completedFuture(ApprovalDecision.APPROVED), store),
                new HookRegistry(Set.of()));
        List<String> order = new ArrayList<>();
        RunTimeline[] received = new RunTimeline[1];
        loop.run("what time is it?", new Conversation("c"), new AgentListener() {
            @Override
            public void onTimeline(RunTimeline timeline) {
                order.add("timeline");
                received[0] = timeline;
            }

            @Override
            public void onFinished(net.talaatharb.harness.agent.AgentRunResult result) {
                order.add("finished");
            }
        }, new AtomicBoolean());

        assertEquals(List.of("timeline", "finished"), order);
        RunTimeline timeline = received[0];
        assertEquals(List.of(TimelineEvent.Kind.CONTEXT, TimelineEvent.Kind.MODEL, TimelineEvent.Kind.TOOL,
                TimelineEvent.Kind.CONTEXT, TimelineEvent.Kind.MODEL, TimelineEvent.Kind.END),
                timeline.events.stream().map(TimelineEvent::kind).toList());
        TimelineEvent firstModel = timeline.events.get(1);
        assertEquals(120, firstModel.promptTokens());
        assertEquals(15, firstModel.completionTokens());
        assertFalse(firstModel.estimated());
        assertTrue(timeline.events.get(4).estimated(), "no usage reported → estimate");
        assertEquals("current_time", timeline.events.get(2).label());
        assertTrue(timeline.events.get(0).sections().stream().anyMatch(x -> x.contributor().equals("tool-catalog")));
        assertEquals("COMPLETED", timeline.events.get(5).label());
        assertTrue(timeline.headline().startsWith("2 model calls · 1 tool · ~"), timeline.headline());
        String text = timeline.toText();
        assertTrue(text.contains("tool-catalog"));
        assertTrue(text.contains("in 120 / out 15 tokens"), text);
        assertFalse(text.contains(SECRET));
    }

    @Test
    void redactedCopyAndPersistenceKeepEventsButNotSecrets() {
        RunTimeline timeline = new RunTimeline("ollama · m");
        ContextBuilder builder = new ContextBuilder(Set.of(new Contributors.SystemPrompt(),
                new Contributors.HistoryWindow()));
        ContextBuild build = builder.buildDetailed(new HarnessSettings(), List.of(ChatMessage.user("hi")), null);
        timeline.context(build, 1, build.messages().size(), build.estimatedTokens());
        timeline.tool("read_file", timeline.elapsed(), false, "failed with " + SECRET);
        timeline.end("ERROR", "boom " + SECRET);
        for (int i = 0; i < RunTimeline.MAX_EVENTS + 5; i++) {
            timeline.repair("again");
        }
        assertEquals(RunTimeline.MAX_EVENTS, timeline.events.size());
        assertEquals(8, timeline.droppedEvents);

        Path db = temp.resolve("h.db");
        ConversationRecord record = ConversationRecord.create();
        record.messages.add(ChatMessage.user("hi"));
        record.summary = "user key " + SECRET;
        record.summarizedCount = 1;
        TurnRecord turn = new TurnRecord("hi", 0);
        turn.timeline = timeline;
        record.turns.add(turn);
        SettingsStore settingsStore = new SettingsStore(null);
        try (ConversationStore store = new ConversationStore(db, settingsStore)) {
            store.save(record);
        }
        try (ConversationStore store = new ConversationStore(db, settingsStore)) {
            ConversationRecord loaded = store.load(record.id).orElseThrow();
            RunTimeline restored = loaded.turns.get(0).timeline;
            assertNotNull(restored);
            assertEquals(RunTimeline.MAX_EVENTS, restored.events.size());
            assertEquals(8, restored.droppedEvents);
            assertEquals("ollama · m", restored.model);
            assertNotNull(restored.events.get(0).sections());
            assertTrue(restored.events.get(1).failed());
            assertFalse(restored.toText().contains(SECRET));
            assertFalse(loaded.summary.contains(SECRET));
            assertEquals(1, loaded.summarizedCount);
        }
    }

    @Test
    void contextBuilderReportsOnlyContributorsThatAddedSomething() {
        HarnessSettings settings = new HarnessSettings();
        settings.context.pinnedContext = "";
        ContextBuilder builder = new ContextBuilder(Set.of(new Contributors.SystemPrompt(),
                new Contributors.PinnedContext(), new Contributors.ConversationSummary(),
                new Contributors.HistoryWindow()));
        ContextBuild plain = builder.buildDetailed(settings, List.of(ChatMessage.user("hello")), null);
        assertEquals(List.of("system-prompt", "history-window"),
                plain.sections().stream().map(x -> x.contributor()).toList());
        assertEquals(1, plain.sections().get(1).messages());

        ContextBuild summarized = builder.buildDetailed(settings, List.of(ChatMessage.user("hello")), "Earlier: x");
        assertTrue(summarized.messages().get(0).content().contains("Earlier: x"));
        assertTrue(summarized.estimatedTokens() > plain.estimatedTokens());
    }
}
