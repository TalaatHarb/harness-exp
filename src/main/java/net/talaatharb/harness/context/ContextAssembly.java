package net.talaatharb.harness.context;

import java.util.ArrayList;
import java.util.List;

import net.talaatharb.harness.agent.ConversationScope;
import net.talaatharb.harness.config.HarnessSettings;
import net.talaatharb.harness.llm.ChatMessage;

/** Mutable working state passed through the context contributors. */
public class ContextAssembly {
    private final HarnessSettings settings;
    private final List<ChatMessage> history;
    private final String latestUserMessage;
    private final String conversationId;
    private final List<String> systemSections = new ArrayList<>();
    private final List<ChatMessage> conversation = new ArrayList<>();

    /** Uses the conversation of the agent run on this thread ({@link ConversationScope}). */
    public ContextAssembly(HarnessSettings settings, List<ChatMessage> history, String latestUserMessage) {
        this(settings, history, latestUserMessage, ConversationScope.current());
    }

    public ContextAssembly(HarnessSettings settings, List<ChatMessage> history, String latestUserMessage,
            String conversationId) {
        this.settings = settings;
        this.history = List.copyOf(history);
        this.latestUserMessage = latestUserMessage;
        this.conversationId = conversationId;
    }

    /** Id of the conversation being answered, or {@code null} if unknown. */
    public String conversationId() {
        return conversationId;
    }

    public HarnessSettings settings() {
        return settings;
    }

    public List<ChatMessage> history() {
        return history;
    }

    public String latestUserMessage() {
        return latestUserMessage;
    }

    public void addSystemSection(String section) {
        if (section != null && !section.isBlank()) {
            systemSections.add(section.strip());
        }
    }

    public List<String> systemSections() {
        return systemSections;
    }

    public List<ChatMessage> conversation() {
        return conversation;
    }
}
