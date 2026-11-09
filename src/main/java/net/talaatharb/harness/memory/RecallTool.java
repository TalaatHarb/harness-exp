package net.talaatharb.harness.memory;

import java.util.List;
import java.util.Map;

import com.google.inject.Inject;

import net.talaatharb.harness.tools.Tool;
import net.talaatharb.harness.tools.ToolArguments;
import net.talaatharb.harness.tools.ToolResult;

/** Searches long-term memory (markdown folder or MapDB, as configured). */
public class RecallTool implements Tool {
    public static final String NAME = "recall";

    private final MemoryService memory;

    @Inject
    public RecallTool(MemoryService memory) {
        this.memory = memory;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "Searches long-term memory saved in earlier conversations (facts, preferences, decisions) and returns "
                + "the best matches with their ids, most relevant first. Use a few keywords as the query; an empty "
                + "query lists the most recent memories.";
    }

    @Override
    public String argumentsHint() {
        return "{\"query\": \"preferred units\", \"limit\": 5}";
    }

    @Override
    public ToolResult execute(Map<String, Object> arguments) {
        Map<String, Object> args = arguments == null ? Map.of() : arguments;
        int max = Math.max(1, memory.settings().maxRecallResults);
        String query;
        int limit;
        try {
            query = ToolArguments.string(args, "query", "").strip();
            limit = Math.min(max, ToolArguments.integer(args, "limit", max, Integer.MAX_VALUE));
        } catch (IllegalArgumentException e) {
            return ToolResult.error(e.getMessage());
        }
        List<MemoryService.Match> matches = memory.recall(query, limit);
        if (matches.isEmpty()) {
            return ToolResult.ok(query.isBlank() ? "Memory is empty." : "No memories match \"" + query + "\".");
        }
        StringBuilder sb = new StringBuilder(matches.size() == 1 ? "1 memory" : matches.size() + " memories")
                .append(query.isBlank() ? " (most recent first):" : " (most relevant first):");
        matches.forEach(m -> sb.append('\n').append(MemoryService.describe(m.entry())));
        return ToolResult.ok(sb.toString());
    }
}
