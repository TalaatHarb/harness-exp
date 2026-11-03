package net.talaatharb.harness.memory;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.inject.Inject;

import net.talaatharb.harness.context.ContextAssembly;
import net.talaatharb.harness.context.ContextContributor;

/** Adds the memories most relevant to the latest user message to the system context. */
public class MemoryContextContributor implements ContextContributor {
    public static final String ID = "memory";
    private static final Logger LOG = LoggerFactory.getLogger(MemoryContextContributor.class);

    private final MemoryService memory;

    @Inject
    public MemoryContextContributor(MemoryService memory) {
        this.memory = memory;
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String description() {
        return "Long-term memories relevant to the latest message (Memory tab)";
    }

    @Override
    public int order() {
        return 150;
    }

    @Override
    public void contribute(ContextAssembly a) {
        int max = a.settings().memory == null ? new MemorySettings().maxContextMemories
                : a.settings().memory.maxContextMemories;
        String message = a.latestUserMessage();
        if (max <= 0 || message == null || message.isBlank()) {
            return;
        }
        List<MemoryService.Match> matches;
        try {
            matches = MemoryService.rank(memory.store(a.settings().memory == null ? MemorySettings.Backend.MARKDOWN
                    : a.settings().memory.backend).list(), message, max).stream().filter(m -> m.score() > 0).toList();
        } catch (RuntimeException e) {
            LOG.error("Could not read memories for the context", e);
            return;
        }
        if (matches.isEmpty()) {
            return;
        }
        StringBuilder sb = new StringBuilder("## Memory\nSaved from earlier conversations; may be relevant here. "
                + "They can be outdated: the user's current messages take precedence.");
        matches.forEach(m -> sb.append('\n').append(MemoryService.describe(m.entry())));
        a.addSystemSection(sb.toString());
    }
}
