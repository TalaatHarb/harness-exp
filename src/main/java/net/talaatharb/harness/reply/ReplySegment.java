package net.talaatharb.harness.reply;

/** One piece of an assistant reply: plain text, a generated UI block, or a block that failed to parse. */
public sealed interface ReplySegment {

    record Text(String text) implements ReplySegment {
    }

    record Ui(UiComponent component) implements ReplySegment {
    }

    record Invalid(String raw, String error) implements ReplySegment {
    }
}
