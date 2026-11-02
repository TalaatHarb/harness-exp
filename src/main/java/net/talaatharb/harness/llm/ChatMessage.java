package net.talaatharb.harness.llm;

import java.util.List;
import java.util.Objects;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Provider-neutral chat message.
 *
 * @param role        who produced the message
 * @param content     textual content
 * @param name        optional name (tool name for {@link Role#TOOL} messages)
 * @param attachments files attached to a user message (never {@code null})
 */
public record ChatMessage(Role role, String content, String name,
        @JsonInclude(JsonInclude.Include.NON_EMPTY) List<Attachment> attachments) {

    public ChatMessage {
        Objects.requireNonNull(role, "role");
        content = content == null ? "" : content;
        attachments = attachments == null ? List.of() : List.copyOf(attachments);
    }

    public ChatMessage(Role role, String content, String name) {
        this(role, content, name, List.of());
    }

    public static ChatMessage system(String content) {
        return new ChatMessage(Role.SYSTEM, content, null);
    }

    public static ChatMessage user(String content) {
        return new ChatMessage(Role.USER, content, null);
    }

    public static ChatMessage user(String content, List<Attachment> attachments) {
        return new ChatMessage(Role.USER, content, null, attachments);
    }

    public static ChatMessage assistant(String content) {
        return new ChatMessage(Role.ASSISTANT, content, null);
    }

    public static ChatMessage tool(String toolName, String content) {
        return new ChatMessage(Role.TOOL, content, toolName);
    }

    /**
     * The text a model sees: the content followed by the inlined text of text/PDF attachments and short notes for
     * images and audio (image bytes are sent separately by providers that support them).
     */
    @JsonIgnore
    public String contentForModel() {
        if (attachments.isEmpty()) {
            return content;
        }
        StringBuilder sb = new StringBuilder(content);
        for (Attachment a : attachments) {
            sb.append(sb.length() == 0 ? "" : "\n\n");
            switch (a.kind()) {
                case TEXT, PDF -> sb.append("<attachment name=\"").append(a.name()).append("\" type=\"")
                        .append(a.mimeType()).append("\"").append(a.truncated() ? " truncated=\"true\"" : "")
                        .append(">\n").append(a.text() == null ? "" : a.text()).append("\n</attachment>");
                case IMAGE -> sb.append("[Image attached: ").append(a.name()).append("]");
                case AUDIO -> sb.append("[Audio attached: ").append(a.name()).append(" (")
                        .append(Attachment.formatSize(a.size())).append("). Its sound can't be sent to the model;")
                        .append(" ask the user for a transcript or summary if you need its content.]");
            }
        }
        return sb.toString();
    }

    @JsonIgnore
    public List<byte[]> images() {
        return attachments.stream().filter(a -> a.kind() == Attachment.Kind.IMAGE).map(Attachment::bytes).toList();
    }
}
