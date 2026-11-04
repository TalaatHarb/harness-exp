package net.talaatharb.harness.agent;

import java.util.ArrayList;
import java.util.List;

import net.talaatharb.harness.llm.ChatMessage;

/**
 * Thread-safe conversation memory (full transcript; the context builder decides what the model sees). Older
 * messages can be compacted into a running {@linkplain #summary() summary} that covers the first
 * {@link #summarizedCount()} messages; the transcript itself is never shortened by compaction.
 */
public class Conversation {
    private final List<ChatMessage> messages = new ArrayList<>();
    private final String id;
    private String summary;
    private int summarizedCount;

    public Conversation() {
        this(null);
    }

    /** @param id the saved conversation id; scopes per-conversation data such as RAG documents */
    public Conversation(String id) {
        this.id = id;
    }

    /** {@code null} for throwaway conversations (e.g. tests). */
    public String id() {
        return id;
    }

    public synchronized void add(ChatMessage message) {
        messages.add(message);
    }

    public synchronized List<ChatMessage> snapshot() {
        return List.copyOf(messages);
    }

    /** Messages not covered by the summary (the whole transcript when nothing was compacted). */
    public synchronized List<ChatMessage> unsummarized() {
        return List.copyOf(messages.subList(Math.min(summarizedCount, messages.size()), messages.size()));
    }

    /** Summary of the first {@link #summarizedCount()} messages, or {@code null}. */
    public synchronized String summary() {
        return summary;
    }

    public synchronized int summarizedCount() {
        return summary == null ? 0 : summarizedCount;
    }

    /** Records that {@code summary} covers the first {@code count} messages; invalid input clears the summary. */
    public synchronized void setSummary(String summary, int count) {
        if (summary == null || summary.isBlank() || count <= 0 || count > messages.size()) {
            clearSummary();
            return;
        }
        this.summary = summary.strip();
        this.summarizedCount = count;
    }

    private void clearSummary() {
        summary = null;
        summarizedCount = 0;
    }

    public synchronized void clear() {
        messages.clear();
        clearSummary();
    }

    /** Drops every message from {@code size} onward (used to rewind before regenerating a turn). */
    public synchronized void truncate(int size) {
        if (size < 0) {
            throw new IllegalArgumentException("size must be >= 0");
        }
        if (size < messages.size()) {
            messages.subList(size, messages.size()).clear();
        }
        if (size < summarizedCount) {
            // The summary describes messages that no longer exist; it is rebuilt on the next overflow.
            clearSummary();
        }
    }

    public synchronized void replaceAll(List<ChatMessage> transcript) {
        messages.clear();
        messages.addAll(transcript);
        clearSummary();
    }

    public synchronized int size() {
        return messages.size();
    }
}
