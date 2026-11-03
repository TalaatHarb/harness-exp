package net.talaatharb.harness.agent;

import java.util.ArrayList;
import java.util.List;

import net.talaatharb.harness.llm.ChatMessage;

/** Thread-safe conversation memory (full transcript; the context builder decides what the model sees). */
public class Conversation {
    private final List<ChatMessage> messages = new ArrayList<>();
    private final String id;

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

    public synchronized void clear() {
        messages.clear();
    }

    /** Drops every message from {@code size} onward (used to rewind before regenerating a turn). */
    public synchronized void truncate(int size) {
        if (size < 0) {
            throw new IllegalArgumentException("size must be >= 0");
        }
        if (size < messages.size()) {
            messages.subList(size, messages.size()).clear();
        }
    }

    public synchronized void replaceAll(List<ChatMessage> transcript) {
        messages.clear();
        messages.addAll(transcript);
    }

    public synchronized int size() {
        return messages.size();
    }
}
