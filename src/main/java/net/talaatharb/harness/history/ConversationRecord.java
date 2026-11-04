package net.talaatharb.harness.history;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import net.talaatharb.harness.llm.ChatMessage;
import net.talaatharb.harness.guardrails.SecretRedactionGuardrail;

/** A persisted conversation: the full transcript the agent sees plus the per-turn outcomes the UI renders. */
@JsonIgnoreProperties(ignoreUnknown = true)
public class ConversationRecord {
    static final int MAX_TITLE_CHARS = 60;
    /** Upper bound for user-chosen titles (generated ones use {@link #MAX_TITLE_CHARS}). */
    public static final int MAX_CUSTOM_TITLE_CHARS = 120;

    public String id;
    public String title;
    public Instant createdAt;
    public Instant updatedAt;
    public String model;
    public List<ChatMessage> messages = new ArrayList<>();
    public List<TurnRecord> turns = new ArrayList<>();

    public static ConversationRecord create() {
        ConversationRecord record = new ConversationRecord();
        record.id = UUID.randomUUID().toString();
        record.createdAt = Instant.now();
        record.updatedAt = record.createdAt;
        return record;
    }

    /** First line of the first request, shortened. */
    public static String titleFor(String userText) {
        String text = userText == null ? "" : SecretRedactionGuardrail.redactTitle(userText).strip();
        int newline = text.indexOf('\n');
        if (newline >= 0) {
            text = text.substring(0, newline).strip();
        }
        if (text.isEmpty()) {
            return "Untitled";
        }
        return text.length() > MAX_TITLE_CHARS ? text.substring(0, MAX_TITLE_CHARS - 1) + "…" : text;
    }

    /** Cleans a user-chosen title: single line, collapsed whitespace, capped length. */
    public static String normalizeTitle(String title) {
        String text = title == null ? "" : SecretRedactionGuardrail.redactTitle(title).replaceAll("\\s+", " ").strip();
        if (text.isEmpty()) {
            throw new IllegalArgumentException("Title must not be blank");
        }
        return text.length() > MAX_CUSTOM_TITLE_CHARS ? text.substring(0, MAX_CUSTOM_TITLE_CHARS) : text;
    }
}
