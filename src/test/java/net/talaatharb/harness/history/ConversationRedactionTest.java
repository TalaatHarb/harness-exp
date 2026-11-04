package net.talaatharb.harness.history;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mapdb.DB;
import org.mapdb.DBMaker;
import org.mapdb.Serializer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import net.talaatharb.harness.agent.AgentRunResult;
import net.talaatharb.harness.config.SettingsStore;
import net.talaatharb.harness.llm.Attachment;
import net.talaatharb.harness.llm.ChatMessage;

class ConversationRedactionTest {
    private static final String SECRET = "sk-proj-" + "a".repeat(100);
    @TempDir
    Path dir;

    private ConversationRecord record() {
        ConversationRecord record = ConversationRecord.create();
        record.title = SECRET;
        TurnRecord turn = new TurnRecord(SECRET, 0);
        turn.complete(new AgentRunResult(AgentRunResult.Status.COMPLETED, SECRET, SECRET, 1, 0));
        record.turns.add(turn);
        record.messages.add(ChatMessage.user(SECRET, List.of(
                new Attachment(SECRET, "text/plain", Attachment.Kind.TEXT, 100, SECRET, null, false))));
        record.messages.add(ChatMessage.tool("test", SECRET));
        return record;
    }

    @Test
    void titlesRedactBeforeTruncationOrWhitespaceFlattening() {
        assertEquals("Use [REDACTED]", ConversationRecord.titleFor("Use " + SECRET));
        assertEquals("[REDACTED]", ConversationRecord.normalizeTitle(SECRET));
        assertEquals("Old [REDACTED]", ConversationRecord.normalizeTitle("Old sk-ab…"));
        assertEquals("[REDACTED]", ConversationRecord.titleFor(
                "-----BEGIN PRIVATE KEY-----\nprivate\n-----END PRIVATE KEY-----"));
    }

    @Test
    void savesEveryTextualHistorySurfaceSafelyAcrossRestart() {
        Path path = dir.resolve("safe.db");
        ConversationRecord record = record();
        try (ConversationStore store = new ConversationStore(path)) {
            store.save(record);
            assertEquals("[REDACTED]", record.turns.get(0).userText);
            assertEquals("[REDACTED]", store.rename(record.id, SECRET));
        }
        try (ConversationStore store = new ConversationStore(path)) {
            ConversationRecord loaded = store.load(record.id).orElseThrow();
            assertEquals("[REDACTED]", loaded.title);
            assertEquals("[REDACTED]", loaded.turns.get(0).reply);
            assertEquals("[REDACTED]", loaded.turns.get(0).reason);
            assertFalse(loaded.messages.get(0).contentForModel().contains(SECRET));
            assertEquals("[REDACTED]", loaded.messages.get(1).content());
            assertEquals("[REDACTED]", store.list().get(0).title());
        }
    }

    @Test
    void legacyTitlesAreSafeInListAndRewrittenWithoutChangingRecency() throws Exception {
        Path path = dir.resolve("legacy.db");
        ConversationRecord record = record();
        Instant updated = record.updatedAt;
        ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        try (DB db = DBMaker.fileDB(path.toFile()).transactionEnable().make()) {
            db.hashMap("conversations", Serializer.STRING, Serializer.STRING).createOrOpen()
                    .put(record.id, mapper.writeValueAsString(record));
            // Write raw JSON to bypass the now-safe ConversationSummary constructor.
            db.hashMap("conversation-index", Serializer.STRING, Serializer.STRING).createOrOpen().put(record.id,
                    "{\"id\":\"" + record.id + "\",\"title\":\"" + SECRET
                    + "\",\"updatedAt\":\"" + updated + "\",\"turns\":1}");
            db.commit();
        }
        try (ConversationStore store = new ConversationStore(path)) {
            assertEquals("[REDACTED]", store.list().get(0).title());
            assertEquals(updated, store.load(record.id).orElseThrow().updatedAt);
        }
        try (DB db = DBMaker.fileDB(path.toFile()).make()) {
            String saved = db.hashMap("conversations", Serializer.STRING, Serializer.STRING).open().get(record.id);
            assertFalse(saved.contains(SECRET), "legacy transcript must be rewritten, not only hidden");
            String summary = db.hashMap("conversation-index", Serializer.STRING, Serializer.STRING).open().get(record.id);
            assertFalse(summary.contains(SECRET));
        }
    }

    @Test
    void disablingRedactionStillNeverLeaksTitles() {
        SettingsStore settings = new SettingsStore(null);
        settings.current().guardrails.enabled = false;
        ConversationRecord record = record();
        try (ConversationStore store = new ConversationStore(dir.resolve("disabled.db"), settings)) {
            store.save(record);
            ConversationRecord loaded = store.load(record.id).orElseThrow();
            assertEquals("[REDACTED]", loaded.title);
            assertEquals(SECRET, loaded.turns.get(0).userText);
            assertEquals(SECRET, loaded.messages.get(0).content());
        }
    }
}
