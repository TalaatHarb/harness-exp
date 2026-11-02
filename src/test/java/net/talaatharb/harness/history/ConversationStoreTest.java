package net.talaatharb.harness.history;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import net.talaatharb.harness.agent.AgentRunResult;
import net.talaatharb.harness.agent.Conversation;
import net.talaatharb.harness.llm.ChatMessage;
import net.talaatharb.harness.llm.Role;

class ConversationStoreTest {
    @TempDir
    Path dir;

    private static ConversationRecord sample(String question) {
        ConversationRecord record = ConversationRecord.create();
        TurnRecord turn = new TurnRecord(question, 0);
        record.messages.add(ChatMessage.user(question));
        record.messages.add(ChatMessage.assistant("```tool_call\n{\"name\":\"current_time\",\"arguments\":{}}\n```"));
        record.messages.add(ChatMessage.tool("current_time", "Result of tool `current_time` (success):\nnoon"));
        record.messages.add(ChatMessage.assistant("It is noon."));
        turn.complete(new AgentRunResult(AgentRunResult.Status.COMPLETED, "It is noon.", null, 2, 1));
        record.turns.add(turn);
        return record;
    }

    @Test
    void savesAndReloadsAcrossRestarts() {
        Path db = dir.resolve("history.db");
        ConversationRecord saved = sample("What time is it?\nPlease be precise.");
        try (ConversationStore store = new ConversationStore(db)) {
            assertTrue(store.persistent());
            store.save(saved);
        }

        try (ConversationStore store = new ConversationStore(db)) {
            List<ConversationSummary> list = store.list();
            assertEquals(1, list.size());
            assertEquals("What time is it?", list.get(0).title());
            assertEquals(1, list.get(0).turns());

            ConversationRecord loaded = store.load(saved.id).orElseThrow();
            assertEquals(4, loaded.messages.size());
            assertEquals(Role.TOOL, loaded.messages.get(2).role());
            assertEquals("current_time", loaded.messages.get(2).name());
            assertEquals(AgentRunResult.Status.COMPLETED, loaded.turns.get(0).status);
            assertEquals("It is noon.", loaded.turns.get(0).toResult().reply());
            assertEquals(saved.createdAt, loaded.createdAt);
        }
    }

    @Test
    void listsMostRecentFirstAndDeletes() throws Exception {
        try (ConversationStore store = new ConversationStore(dir.resolve("h.db"))) {
            ConversationRecord first = sample("first");
            ConversationRecord second = sample("second");
            store.save(first);
            Thread.sleep(5);
            store.save(second);
            assertEquals(List.of("second", "first"), store.list().stream().map(ConversationSummary::title).toList());

            Thread.sleep(5);
            store.save(first);
            assertEquals("first", store.list().get(0).title(), "continuing a chat moves it to the top");

            assertTrue(store.delete(first.id));
            assertFalse(store.load(first.id).isPresent());
            assertEquals(1, store.list().size());
        }
    }

    @Test
    void fallsBackToMemoryWhenFileIsLocked() throws Exception {
        // MapDB leaks a handle from the failed second open on Windows, so this can't use the auto-deleted @TempDir.
        Path lockDir = java.nio.file.Files.createTempDirectory("harness-history-lock");
        Path db = lockDir.resolve("locked.db");
        try (ConversationStore owner = new ConversationStore(db);
                ConversationStore second = new ConversationStore(db)) {
            assertTrue(owner.persistent());
            assertFalse(second.persistent());
            second.save(sample("in memory"));
            assertEquals(1, second.list().size());
        }
        lockDir.toFile().deleteOnExit();
    }

    @Test
    void renamePersistsWithoutReorderingAndSurvivesLaterSaves() throws Exception {
        Path db = dir.resolve("rename.db");
        ConversationRecord older = sample("older");
        ConversationRecord newer = sample("newer");
        try (ConversationStore store = new ConversationStore(db)) {
            store.save(older);
            Thread.sleep(5);
            store.save(newer);
            java.time.Instant before = store.list().get(1).updatedAt();

            assertEquals("My  renamed chat".replaceAll("\\s+", " "),
                    store.rename(older.id, "  My \n renamed\tchat "));

            List<ConversationSummary> list = store.list();
            assertEquals(List.of("newer", "My renamed chat"), list.stream().map(ConversationSummary::title).toList());
            assertEquals(before, list.get(1).updatedAt(), "renaming must not move the chat to the top");

            // The UI keeps its open record in sync, so continuing the chat keeps the new name.
            older.title = store.load(older.id).orElseThrow().title;
            store.save(older);
            assertEquals("My renamed chat", store.list().get(0).title());
        }
        try (ConversationStore store = new ConversationStore(db)) {
            assertEquals("My renamed chat", store.load(older.id).orElseThrow().title);
        }
    }

    @Test
    void renameRejectsBlankTitlesAndUnknownIdsAndCapsLength() {
        try (ConversationStore store = new ConversationStore(dir.resolve("r.db"))) {
            ConversationRecord record = sample("q");
            store.save(record);
            org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                    () -> store.rename(record.id, "   "));
            org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                    () -> store.rename("missing", "x"));
            assertEquals("q", store.list().get(0).title());
            String stored = store.rename(record.id, "y".repeat(500));
            assertEquals(ConversationRecord.MAX_CUSTOM_TITLE_CHARS, stored.length());
        }
    }

    @Test
    void titlesUseFirstLineAndAreShortened() {
        assertEquals("Untitled", ConversationRecord.titleFor("  "));
        String title = ConversationRecord.titleFor("x".repeat(200));
        assertEquals(ConversationRecord.MAX_TITLE_CHARS, title.length());
        assertTrue(title.endsWith("…"));
    }

    @Test
    void conversationTruncatesForRegenerate() {
        Conversation conversation = new Conversation();
        sample("q").messages.forEach(conversation::add);
        conversation.truncate(1);
        assertEquals(1, conversation.size());
        conversation.truncate(5);
        assertEquals(1, conversation.size());
        conversation.replaceAll(List.of());
        assertEquals(0, conversation.size());
    }
}
