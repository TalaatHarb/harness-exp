package net.talaatharb.harness.llm;

/**
 * Result of a model invocation.
 *
 * @param promptTokens     provider-reported input tokens, or {@code null} when the provider didn't report them
 * @param completionTokens provider-reported output tokens, or {@code null} when the provider didn't report them
 */
public record ModelReply(String content, String thinking, long durationMillis, Integer promptTokens,
        Integer completionTokens) {

    public ModelReply {
        content = content == null ? "" : content;
    }

    public ModelReply(String content, String thinking, long durationMillis) {
        this(content, thinking, durationMillis, null, null);
    }

    static Integer count(com.fasterxml.jackson.databind.JsonNode node) {
        return node != null && node.isNumber() ? node.asInt() : null;
    }
}
