package net.talaatharb.harness.context;

import java.util.List;

import net.talaatharb.harness.llm.ChatMessage;

/** An assembled context plus the per-contributor size accounting shown in the run timeline. */
public record ContextBuild(List<ChatMessage> messages, List<ContextSection> sections) {

    public ContextBuild {
        messages = List.copyOf(messages);
        sections = List.copyOf(sections);
    }

    public int estimatedTokens() {
        return TokenEstimator.estimate(messages);
    }
}
