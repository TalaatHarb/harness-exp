package net.talaatharb.harness.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

import org.junit.jupiter.api.Test;

import net.talaatharb.harness.approval.ApprovalDecision;
import net.talaatharb.harness.approval.ApprovalService;
import net.talaatharb.harness.config.HarnessSettings;
import net.talaatharb.harness.config.SettingsStore;
import net.talaatharb.harness.context.ContextBuilder;
import net.talaatharb.harness.context.ContextSettings;
import net.talaatharb.harness.context.Contributors;
import net.talaatharb.harness.guardrails.GuardrailService;
import net.talaatharb.harness.guardrails.SecretRedactionGuardrail;
import net.talaatharb.harness.llm.ChatMessage;
import net.talaatharb.harness.llm.ModelProviderRegistry;
import net.talaatharb.harness.llm.ModelRequest;
import net.talaatharb.harness.tools.ToolRegistry;
import net.talaatharb.harness.tools.mcp.McpToolSource;
import net.talaatharb.harness.trace.RunTimeline;
import net.talaatharb.harness.trace.TimelineEvent;

class ContextCompactionTest {
    private static final String SECRET = "sk-" + "b".repeat(24);

    private SettingsStore store;
    @org.junit.jupiter.api.io.TempDir
    java.nio.file.Path temp;

    private AgentLoop loop(AgentLoopTest.ScriptedProvider provider, java.util.function.Consumer<HarnessSettings> c) {
        store = new SettingsStore(null);
        HarnessSettings s = store.copy();
        s.model.provider = "fake";
        s.context.maxHistoryMessages = 4;
        s.context.compactionKeepRecent = 2;
        c.accept(s);
        store.replace(s);
        ToolRegistry tools = new ToolRegistry(Set.of(), McpToolSource.NONE,
                new net.talaatharb.harness.tools.dynamic.DynamicToolRegistry(temp));
        ContextBuilder context = new ContextBuilder(Set.of(new Contributors.SystemPrompt(),
                new Contributors.ConversationSummary(), new Contributors.HistoryWindow()));
        GuardrailService guardrails = new GuardrailService(Set.of(new SecretRedactionGuardrail()));
        ApprovalService approvals = new ApprovalService(
                req -> java.util.concurrent.CompletableFuture.completedFuture(ApprovalDecision.APPROVED), store);
        return new AgentLoop(store, new ModelProviderRegistry(Set.of(provider)), context, tools, guardrails,
                approvals, new HookRegistry(Set.of()));
    }

    private static Conversation history(int exchanges) {
        Conversation conversation = new Conversation("c1");
        for (int i = 1; i <= exchanges; i++) {
            conversation.add(ChatMessage.user("question " + i + (i == 1 ? " my key is " + SECRET : "")));
            conversation.add(ChatMessage.assistant("answer " + i));
        }
        return conversation;
    }

    private static boolean isSummaryRequest(ModelRequest request) {
        return request.messages().get(0).content().contains("running summary");
    }

    @Test
    void planKeepsRecentMessagesFromAUserTurnAndHonoursSettings() {
        ContextSettings settings = new ContextSettings();
        settings.maxHistoryMessages = 4;
        settings.compactionKeepRecent = 3;
        Conversation conversation = history(3);
        conversation.add(ChatMessage.user("latest"));

        var plan = ConversationCompactor.plan(conversation, settings).orElseThrow();
        assertEquals(4, plan.end(), "kept part starts at the user message 'question 3'");
        assertEquals(4, plan.messages().size());

        settings.compactionEnabled = false;
        assertTrue(ConversationCompactor.plan(conversation, settings).isEmpty());

        settings.compactionEnabled = true;
        settings.maxHistoryMessages = 50;
        settings.compactionTokenBudget = 0;
        assertTrue(ConversationCompactor.plan(conversation, settings).isEmpty(), "no overflow");
        settings.compactionTokenBudget = 5;
        assertTrue(ConversationCompactor.plan(conversation, settings).isPresent(), "token budget overflow");
    }

    @Test
    void overflowingHistoryIsSummarizedRedactedAndUsedInsteadOfOldMessages() {
        List<ModelRequest> summaries = new ArrayList<>();
        Function<ModelRequest, String> script = request -> {
            if (isSummaryRequest(request)) {
                summaries.add(request);
                return "<think>hmm</think>User asked questions 1-4 and got answers.";
            }
            return "final answer";
        };
        AgentLoopTest.ScriptedProvider provider = new AgentLoopTest.ScriptedProvider(script);
        AgentLoop loop = loop(provider, s -> { });
        Conversation conversation = history(4);
        List<RunTimeline> timelines = new ArrayList<>();
        List<String> notices = new ArrayList<>();

        AgentRunResult result = loop.run("question 5", conversation, new AgentListener() {
            @Override
            public void onTimeline(RunTimeline timeline) {
                timelines.add(timeline);
            }

            @Override
            public void onNotice(String notice) {
                notices.add(notice);
            }
        }, new AtomicBoolean());

        assertEquals(AgentRunResult.Status.COMPLETED, result.status());
        assertEquals(1, summaries.size());
        String sent = summaries.get(0).messages().get(1).content();
        assertFalse(sent.contains(SECRET), "secrets never reach the summarizer");
        assertTrue(sent.contains("question 1"));
        assertEquals("User asked questions 1-4 and got answers.", conversation.summary());
        // 9 messages, keep 2 → the verbatim part is moved back to start at the user message "question 4".
        assertEquals(6, conversation.summarizedCount());
        assertEquals(10, conversation.size(), "the transcript itself is never shortened");

        ModelRequest answer = provider.requests.get(provider.requests.size() - 1);
        assertTrue(answer.messages().get(0).content().contains("Earlier conversation (summary)"));
        assertTrue(answer.messages().stream().noneMatch(m -> m.content().contains("question 1")));
        assertEquals("question 4", answer.messages().get(1).content());
        assertTrue(notices.stream().anyMatch(n -> n.startsWith("Compacting 6 older messages")), notices.toString());

        RunTimeline timeline = timelines.get(0);
        List<TimelineEvent.Kind> kinds = timeline.events.stream().map(TimelineEvent::kind).toList();
        assertEquals(List.of(TimelineEvent.Kind.COMPACTION, TimelineEvent.Kind.CONTEXT, TimelineEvent.Kind.MODEL,
                TimelineEvent.Kind.END), kinds);
        assertTrue(timeline.events.get(1).sections().stream()
                .anyMatch(s -> s.contributor().equals("conversation-summary")));
        assertTrue(timeline.headline().contains("compacted"), timeline.headline());

        // The next turn extends the existing summary instead of re-reading the whole transcript.
        loop.run("question 6", conversation, new AgentListener() { }, new AtomicBoolean());
        assertEquals(2, summaries.size());
        assertTrue(summaries.get(1).messages().get(1).content()
                .contains("<<<BEGIN EXCERPT\nPrevious summary:\nUser asked questions 1-4"));
    }

    @Test
    void failedCompactionFallsBackToTheWindowAndIsVisibleInTheTimeline() {
        AgentLoopTest.ScriptedProvider provider = new AgentLoopTest.ScriptedProvider(request -> {
            if (isSummaryRequest(request)) {
                throw new IllegalStateException("summarizer down " + SECRET);
            }
            return "still answered";
        });
        AgentLoop loop = loop(provider, s -> { });
        Conversation conversation = history(4);
        List<RunTimeline> timelines = new ArrayList<>();
        List<String> notices = new ArrayList<>();
        AgentRunResult result = loop.run("question 5", conversation, new AgentListener() {
            @Override
            public void onTimeline(RunTimeline timeline) {
                timelines.add(timeline);
            }

            @Override
            public void onNotice(String notice) {
                notices.add(notice);
            }
        }, new AtomicBoolean());

        assertEquals(AgentRunResult.Status.COMPLETED, result.status());
        assertNull(conversation.summary());
        assertTrue(notices.stream().anyMatch(n -> n.startsWith("Context compaction failed")));
        assertTrue(notices.stream().noneMatch(n -> n.contains(SECRET)));
        TimelineEvent compaction = timelines.get(0).events.get(0);
        assertEquals(TimelineEvent.Kind.COMPACTION, compaction.kind());
        assertTrue(compaction.failed());
        assertFalse(compaction.detail().contains(SECRET));
    }

    @Test
    void disabledCompactionKeepsTheSlidingWindowWithoutExtraModelCalls() {
        AgentLoopTest.ScriptedProvider provider = AgentLoopTest.ScriptedProvider.of("ok");
        AgentLoop loop = loop(provider, s -> s.context.compactionEnabled = false);
        Conversation conversation = history(4);
        loop.run("question 5", conversation, new AgentListener() { }, new AtomicBoolean());
        assertEquals(1, provider.requests.size());
        assertNull(conversation.summary());
    }

    @Test
    void rewindingBeforeTheSummarizedPartDropsTheSummary() {
        Conversation conversation = history(3);
        conversation.setSummary("summary", 4);
        assertEquals(2, conversation.unsummarized().size());
        conversation.truncate(5);
        assertNotNull(conversation.summary());
        conversation.truncate(3);
        assertNull(conversation.summary());
        assertEquals(0, conversation.summarizedCount());
        conversation.setSummary("too far", 99);
        assertNull(conversation.summary());
    }
}
