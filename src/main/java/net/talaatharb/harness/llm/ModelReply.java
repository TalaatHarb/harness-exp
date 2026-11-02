package net.talaatharb.harness.llm;

/** Result of a model invocation. */
public record ModelReply(String content, String thinking, long durationMillis) {

    public ModelReply {
        content = content == null ? "" : content;
    }
}
