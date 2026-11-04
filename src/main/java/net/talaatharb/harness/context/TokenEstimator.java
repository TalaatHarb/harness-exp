package net.talaatharb.harness.context;

import java.util.List;

import net.talaatharb.harness.llm.ChatMessage;

/**
 * Provider-neutral token estimate (about four characters per token plus a small per-message overhead). Used for
 * budgets and the run timeline when a provider doesn't report real counts; always labelled as an estimate.
 */
public final class TokenEstimator {
    static final int MESSAGE_OVERHEAD = 4;

    private TokenEstimator() {
    }

    public static int estimate(String text) {
        return text == null || text.isEmpty() ? 0 : (text.length() + 3) / 4;
    }

    public static int estimate(ChatMessage message) {
        return MESSAGE_OVERHEAD + estimate(message.contentForModel());
    }

    public static int estimate(List<ChatMessage> messages) {
        return messages.stream().mapToInt(TokenEstimator::estimate).sum();
    }
}
