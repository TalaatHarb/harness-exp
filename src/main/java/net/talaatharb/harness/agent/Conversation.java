package net.talaatharb.harness.agent;

import java.util.ArrayList;
import java.util.List;

import net.talaatharb.harness.llm.ChatMessage;

/** Thread-safe conversation memory (full transcript; the context builder decides what the model sees). */
public class Conversation {
    private final List<ChatMessage> messages = new ArrayList<>();

    public synchronized void add(ChatMessage message) {
        messages.add(message);
    }

    public synchronized List<ChatMessage> snapshot() {
        return List.copyOf(messages);
    }

    public synchronized void clear() {
        messages.clear();
    }

    public synchronized int size() {
        return messages.size();
    }
}
