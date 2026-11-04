package net.talaatharb.harness.history;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

import org.mapdb.DB;
import org.mapdb.DBMaker;
import org.mapdb.HTreeMap;
import org.mapdb.Serializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.google.inject.name.Named;

import net.talaatharb.harness.config.SettingsStore;
import net.talaatharb.harness.guardrails.SecretRedactionGuardrail;

/**
 * MapDB-backed conversation history. Records are stored as JSON strings (schema-tolerant across versions) in a
 * transactional file DB, with a separate summary index for the history list. If the file can't be opened (e.g.
 * another instance holds the lock) it falls back to an in-memory DB so the app still works.
 */
@Singleton
public class ConversationStore implements AutoCloseable {
    public static final String DB_PATH = "historyDbPath";
    private static final Logger LOG = LoggerFactory.getLogger(ConversationStore.class);

    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    private final DB db;
    private final HTreeMap<String, String> conversations;
    private final HTreeMap<String, String> index;
    private final boolean persistent;
    private final SettingsStore settings;

    public ConversationStore(@Named(DB_PATH) Path path) {
        this(path, new SettingsStore(null));
    }

    @Inject
    public ConversationStore(@Named(DB_PATH) Path path, SettingsStore settings) {
        this.settings = settings;
        DB opened = open(path);
        persistent = opened != null;
        db = opened != null ? opened : DBMaker.memoryDB().make();
        conversations = db.hashMap("conversations", Serializer.STRING, Serializer.STRING).createOrOpen();
        index = db.hashMap("conversation-index", Serializer.STRING, Serializer.STRING).createOrOpen();
    }

    private static DB open(Path path) {
        try {
            Path parent = path.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            return DBMaker.fileDB(path.toFile()).transactionEnable().closeOnJvmShutdown().make();
        } catch (IOException | RuntimeException e) {
            LOG.error("Could not open conversation history at {}; history will not be kept after exit", path, e);
            return null;
        }
    }

    /** {@code false} when running on the in-memory fallback. */
    public boolean persistent() {
        return persistent;
    }

    /** Text of the first request, or the first attachment name when only files were sent. */
    private static String firstRequest(ConversationRecord record) {
        TurnRecord first = record.turns.get(0);
        if (first.userText != null && !first.userText.isBlank()) {
            return first.userText;
        }
        if (first.messageStart >= 0 && first.messageStart < record.messages.size()) {
            var attachments = record.messages.get(first.messageStart).attachments();
            if (!attachments.isEmpty()) {
                return attachments.get(0).name();
            }
        }
        return first.userText;
    }

    public synchronized void save(ConversationRecord record) {
        record.updatedAt = Instant.now();
        if (record.title == null || record.title.isBlank()) {
            record.title = record.turns.isEmpty() ? "Untitled" : ConversationRecord.titleFor(firstRequest(record));
        }
        sanitize(record);
        try {
            conversations.put(record.id, mapper.writeValueAsString(record));
            index.put(record.id, mapper.writeValueAsString(
                    new ConversationSummary(record.id, record.title, record.updatedAt, record.turns.size())));
            db.commit();
        } catch (IOException | RuntimeException e) {
            db.rollback();
            throw new IllegalStateException("Could not save conversation " + record.id, e);
        }
    }

    public synchronized Optional<ConversationRecord> load(String id) {
        String json = id == null ? null : conversations.get(id);
        if (json == null) {
            return Optional.empty();
        }
        try {
            ConversationRecord record = mapper.readValue(json, ConversationRecord.class);
            sanitize(record);
            String safeJson = mapper.writeValueAsString(record);
            if (!safeJson.equals(json)) {
                conversations.put(id, safeJson);
                index.put(id, mapper.writeValueAsString(
                        new ConversationSummary(id, record.title, record.updatedAt, record.turns.size())));
                db.commit();
            }
            return Optional.of(record);
        } catch (IOException e) {
            LOG.error("Conversation {} is unreadable", id, e);
            return Optional.empty();
        }
    }

    /** Most recently updated first. */
    public synchronized List<ConversationSummary> list() {
        List<ConversationSummary> result = new ArrayList<>();
        for (var entry : index.entrySet()) {
            try {
                ConversationSummary summary = mapper.readValue(entry.getValue(), ConversationSummary.class);
                String safeJson = mapper.writeValueAsString(summary);
                if (!safeJson.equals(entry.getValue())) {
                    // Repair legacy index and transcript titles without changing recency.
                    load(entry.getKey());
                    index.put(entry.getKey(), safeJson);
                    db.commit();
                }
                result.add(summary);
            } catch (IOException e) {
                LOG.warn("Skipping unreadable history entry {}", entry.getKey(), e);
            }
        }
        result.sort(Comparator.comparing(ConversationSummary::updatedAt,
                Comparator.nullsLast(Comparator.reverseOrder())));
        return result;
    }

    private void sanitize(ConversationRecord record) {
        record.title = SecretRedactionGuardrail.redactTitle(record.title);
        record.model = SecretRedactionGuardrail.redact(record.model, settings.current());
        record.messages = new ArrayList<>(record.messages.stream()
                .map(message -> SecretRedactionGuardrail.sanitizeMessage(message, settings.current())).toList());
        record.summary = SecretRedactionGuardrail.redact(record.summary, settings.current());
        for (TurnRecord turn : record.turns) {
            turn.userText = SecretRedactionGuardrail.redact(turn.userText, settings.current());
            turn.reply = SecretRedactionGuardrail.redact(turn.reply, settings.current());
            turn.reason = SecretRedactionGuardrail.redact(turn.reason, settings.current());
            if (turn.timeline != null) {
                turn.timeline = turn.timeline.redacted(
                        text -> SecretRedactionGuardrail.redact(text, settings.current()));
            }
        }
    }

    /**
     * Changes only the title; {@code updatedAt} is kept so renaming doesn't reorder the history list.
     *
     * @return the normalized title that was stored
     * @throws IllegalArgumentException if the title is blank or the conversation doesn't exist
     */
    public synchronized String rename(String id, String title) {
        String normalized = ConversationRecord.normalizeTitle(title);
        ConversationRecord record = load(id)
                .orElseThrow(() -> new IllegalArgumentException("No saved conversation " + id));
        record.title = normalized;
        try {
            conversations.put(id, mapper.writeValueAsString(record));
            index.put(id, mapper.writeValueAsString(
                    new ConversationSummary(id, normalized, record.updatedAt, record.turns.size())));
            db.commit();
        } catch (IOException | RuntimeException e) {
            db.rollback();
            throw new IllegalStateException("Could not rename conversation " + id, e);
        }
        return normalized;
    }

    public synchronized boolean delete(String id) {
        boolean removed = conversations.remove(id) != null;
        index.remove(id);
        db.commit();
        return removed;
    }

    @Override
    public synchronized void close() {
        if (!db.isClosed()) {
            db.close();
        }
    }
}
