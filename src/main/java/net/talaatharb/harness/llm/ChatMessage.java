package net.talaatharb.harness.llm;

import java.util.Objects;

/**
 * Provider-neutral chat message.
 *
 * @param role    who produced the message
 * @param content textual content
 * @param name    optional name (tool name for {@link Role#TOOL} messages)
 */
public record ChatMessage(Role role, String content, String name) {

    public ChatMessage {
        Objects.requireNonNull(role, "role");
        content = content == null ? "" : content;
    }

    public static ChatMessage system(String content) {
        return new ChatMessage(Role.SYSTEM, content, null);
    }

    public static ChatMessage user(String content) {
        return new ChatMessage(Role.USER, content, null);
    }

    public static ChatMessage assistant(String content) {
        return new ChatMessage(Role.ASSISTANT, content, null);
    }

    public static ChatMessage tool(String toolName, String content) {
        return new ChatMessage(Role.TOOL, content, toolName);
    }
}
