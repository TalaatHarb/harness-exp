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
        String latestUser = history.stream().filter(m -> m.role() == Role.USER).reduce((a, b) -> b)
                .map(ChatMessage::content).orElse("");
        ContextAssembly assembly = new ContextAssembly(settings, history, latestUser);
        for (ContextContributor c : contributors) {
            if (!settings.context.disabledContributors.contains(c.id())) {
                c.contribute(assembly);
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
        return messages;
    }
}
