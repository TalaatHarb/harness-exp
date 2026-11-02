package net.talaatharb.harness.llm;

import java.util.List;

/** A single model invocation: which model and the fully assembled context. */
public record ModelRequest(String model, List<ChatMessage> messages, boolean stream) {

    public ModelRequest {
        messages = List.copyOf(messages);
    }
}
