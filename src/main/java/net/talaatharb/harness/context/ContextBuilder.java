package net.talaatharb.harness.context;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

import com.google.inject.Inject;
import com.google.inject.Singleton;

import net.talaatharb.harness.config.HarnessSettings;
import net.talaatharb.harness.llm.ChatMessage;
import net.talaatharb.harness.llm.Role;

/** Runs the enabled contributors and produces the final message list sent to the model. */
@Singleton
public class ContextBuilder {
    private final List<ContextContributor> contributors;

    @Inject
    public ContextBuilder(Set<ContextContributor> contributors) {
        this.contributors = contributors.stream().sorted(Comparator.comparingInt(ContextContributor::order)).toList();
    }

    public List<ContextContributor> contributors() {
        return contributors;
    }

    public List<ChatMessage> build(HarnessSettings settings, List<ChatMessage> history) {
        return buildDetailed(settings, history, null).messages();
    }

    /**
     * Builds the context and records how much each contributor added.
     *
     * @param history             messages not covered by {@code conversationSummary}
     * @param conversationSummary summary of older, compacted messages ({@code null} if none)
     */
    public ContextBuild buildDetailed(HarnessSettings settings, List<ChatMessage> history,
            String conversationSummary) {
        String latestUser = history.stream().filter(m -> m.role() == Role.USER).reduce((a, b) -> b)
                .map(ChatMessage::content).orElse("");
        ContextAssembly assembly = new ContextAssembly(settings, history, latestUser);
        assembly.setConversationSummary(conversationSummary);
        List<ContextSection> sections = new ArrayList<>();
        for (ContextContributor c : contributors) {
            if (settings.context.disabledContributors.contains(c.id())) {
                continue;
            }
            int sectionCount = assembly.systemSections().size();
            int messageCount = assembly.conversation().size();
            c.contribute(assembly);
            List<String> addedSections = assembly.systemSections().subList(
                    Math.min(sectionCount, assembly.systemSections().size()), assembly.systemSections().size());
            List<ChatMessage> addedMessages = assembly.conversation().subList(
                    Math.min(messageCount, assembly.conversation().size()), assembly.conversation().size());
            int chars = addedSections.stream().mapToInt(String::length).sum()
                    + addedMessages.stream().mapToInt(m -> m.contentForModel().length()).sum();
            int tokens = addedSections.stream().mapToInt(TokenEstimator::estimate).sum()
                    + TokenEstimator.estimate(addedMessages);
            if (chars > 0 || !addedMessages.isEmpty()) {
                sections.add(new ContextSection(c.id(), addedMessages.size(), chars, tokens));
            }
        }
        List<ChatMessage> messages = new ArrayList<>();
        if (!assembly.systemSections().isEmpty()) {
            messages.add(ChatMessage.system(String.join("\n\n", assembly.systemSections())));
        }
        if (assembly.conversation().isEmpty() && !latestUser.isEmpty()) {
            messages.add(ChatMessage.user(latestUser));
        } else {
            messages.addAll(assembly.conversation());
        }
        return new ContextBuild(messages, sections);
    }
}
