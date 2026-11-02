package net.talaatharb.harness.llm;

import java.util.Base64;
import java.util.Objects;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * A file attached to a user message.
 *
 * @param name      file name shown to the user and the model
 * @param mimeType  best-effort MIME type
 * @param kind      how the harness hands it to the model
 * @param size      original size in bytes
 * @param text      extracted text ({@link Kind#TEXT}, {@link Kind#PDF}) or speech transcript ({@link Kind#AUDIO});
 *                  {@code null} otherwise
 * @param data      base64 content ({@link Kind#IMAGE}, {@link Kind#AUDIO}); {@code null} otherwise
 * @param truncated whether {@code text} was cut to the configured limit
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Attachment(String name, String mimeType, Kind kind, long size, String text, String data,
        boolean truncated) {

    public enum Kind {
        /** Plain text / markdown: inlined into the message. */
        TEXT,
        /** PDF: its extracted text is inlined. */
        PDF,
        /** Picture: sent as an image to vision models. */
        IMAGE,
        /** Audio: kept with the chat and playable; models get its speech transcript when available, else a note. */
        AUDIO
    }

    public Attachment {
        Objects.requireNonNull(kind, "kind");
        name = name == null || name.isBlank() ? "attachment" : name;
        mimeType = mimeType == null ? "application/octet-stream" : mimeType;
    }

    @JsonIgnore
    public byte[] bytes() {
        return data == null ? new byte[0] : Base64.getDecoder().decode(data);
    }

    public Attachment withText(String newText) {
        return new Attachment(name, mimeType, kind, size, newText, data, truncated);
    }

    /** Sets {@code text}, cutting it to {@code maxChars}. */
    public Attachment withText(String newText, int maxChars) {
        boolean cut = newText != null && newText.length() > maxChars;
        return new Attachment(name, mimeType, kind, size, cut ? newText.substring(0, maxChars) : newText, data, cut);
    }

    /** Human-readable size, e.g. {@code 1.2 MB}. */
    public static String formatSize(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        if (bytes < 1024 * 1024) {
            return String.format(java.util.Locale.ROOT, "%.1f KB", bytes / 1024.0);
        }
        return String.format(java.util.Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024));
    }
}
