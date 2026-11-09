package net.talaatharb.harness.memory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.inject.Inject;

import net.talaatharb.harness.tools.RiskLevel;
import net.talaatharb.harness.tools.Tool;
import net.talaatharb.harness.tools.ToolArguments;
import net.talaatharb.harness.tools.ToolResult;

/** Saves approved facts to long-term memory. Batches are validated first, but writes are not transactional. */
public class RememberTool implements Tool {
    public static final String NAME = "remember";
    public static final int MAX_FACTS = 20;
    private static final Logger LOG = LoggerFactory.getLogger(RememberTool.class);

    private final MemoryService memory;

    @Inject
    public RememberTool(MemoryService memory) {
        this.memory = memory;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "Saves durable facts, preferences or decisions to long-term memory for later conversations, subject "
                + "to tool approval. When asked to remember several facts, include ALL requested facts in facts "
                + "(up to " + MAX_FACTS + "); do not silently omit facts or claim unsaved facts were remembered. "
                + "Use one short, self-contained text per fact. Supply either text (single, memory alias supported) "
                + "or facts (strings or objects with text, optional tags and replace_id). Shared tags are allowed. "
                + "Only identical text is deduplicated. To correct a memory use its recalled id as replace_id. "
                + "All input is validated before writing, but batches are NOT atomic: results report each success "
                + "and failure, with exact saved text. Never store passwords, keys or other secrets.";
    }

    @Override
    public String argumentsHint() {
        return "{\"facts\": [{\"text\": \"The user prefers metric units\", \"tags\": [\"preferences\"]}, "
                + "\"The user's dog is named Luna\"]} OR "
                + "{\"text\": \"one fact\", \"tags\": [\"optional\"], \"replace_id\": \"optional existing id\"}";
    }

    /** Memories end up in every later prompt, so writes are sensitive (approval per the policy). */
    @Override
    public RiskLevel risk() {
        return RiskLevel.SENSITIVE;
    }

    @Override
    public ToolResult execute(Map<String, Object> arguments) {
        Map<String, Object> args = arguments == null ? Map.of() : arguments;
        List<Fact> facts;
        MemoryStore store;
        try {
            facts = parse(args);
            store = memory.active();
            Set<String> replacements = new HashSet<>();
            for (Fact fact : facts) {
                if (!fact.replaceId().isEmpty() && !replacements.add(fact.replaceId())) {
                    throw new IllegalArgumentException("A batch may replace each memory id only once");
                }
                if (!fact.replaceId().isEmpty() && store.get(fact.replaceId()).isEmpty()) {
                    throw new IllegalArgumentException("No memory with id '" + fact.replaceId()
                            + "' (use recall to find ids)");
                }
            }
        } catch (IllegalArgumentException e) {
            return ToolResult.error("Validation failed; no facts written. " + e.getMessage());
        } catch (RuntimeException e) {
            return ToolResult.error("Could not validate memories; no facts written. " + e.getMessage());
        }
        StringBuilder output = new StringBuilder();
        int successful = 0;
        for (int i = 0; i < facts.size(); i++) {
            Fact fact = facts.get(i);
            if (facts.size() > 1) {
                output.append("Fact ").append(i + 1).append(": ");
            }
            try {
                MemoryService.RememberResult r = memory.remember(store, fact.text(), fact.tags(), fact.replaceId(),
                        MemoryEntry.SOURCE_AGENT);
                successful++;
                output.append(switch (r.outcome()) {
                    case ADDED -> "Remembered as [";
                    case UPDATED -> "Updated memory [";
                    case DUPLICATE -> "Already remembered as [";
                }).append(r.entry().id).append("]. Exact saved text:\n").append(r.entry().text)
                        .append("\nSaved tags: ").append(r.entry().tags).append('\n');
            } catch (IOException | RuntimeException e) {
                LOG.error("remember fact {} failed", i + 1, e);
                output.append("FAILED (persistence not confirmed): ").append(e.getMessage())
                        .append("\nRequested text:\n").append(fact.text()).append('\n');
            }
        }
        if (successful != facts.size()) {
            output.append(successful == 0 ? "Failure: " : "Partial failure: ")
                    .append(successful).append(" of ").append(facts.size())
                    .append(" facts confirmed saved/already present. Successful writes were NOT rolled back.");
            return ToolResult.error(output.toString());
        }
        return ToolResult.ok(output.toString().stripTrailing());
    }

    private record Fact(String text, List<String> tags, String replaceId) {
    }

    private static List<Fact> parse(Map<String, Object> args) {
        if (!Set.of("text", "memory", "facts", "tags", "replace_id").containsAll(args.keySet())) {
            throw new IllegalArgumentException("Unknown remember argument; use text/memory or facts, tags, replace_id");
        }
        List<String> sharedTags = validatedTags(args.get("tags"));
        Object batch = args.get("facts");
        boolean hasBatch = !ToolArguments.omitted(batch) && !(batch instanceof List<?> list && list.isEmpty());
        boolean hasText = args.get("text") != null && !str(args.get("text")).isEmpty();
        boolean hasMemory = args.get("memory") != null && !str(args.get("memory")).isEmpty();
        if (hasBatch) {
            if (hasText || hasMemory || !ToolArguments.omitted(args.get("replace_id"))) {
                throw new IllegalArgumentException("Supply facts OR single text/memory with replace_id, not both");
            }
            if (!(args.get("facts") instanceof List<?> list) || list.isEmpty() || list.size() > MAX_FACTS) {
                throw new IllegalArgumentException("facts must be a nonempty array of at most " + MAX_FACTS + " facts");
            }
            List<Fact> facts = new ArrayList<>();
            for (Object value : list) {
                if (value instanceof String text) {
                    facts.add(new Fact(MemoryService.validateText(text), sharedTags, ""));
                } else if (value instanceof Map<?, ?> fact) {
                    if (!Set.of("text", "tags", "replace_id").containsAll(fact.keySet())) {
                        throw new IllegalArgumentException("Fact objects accept text, tags and replace_id only");
                    }
                    List<String> tags = new ArrayList<>(sharedTags);
                    tags.addAll(validatedTags(fact.get("tags")));
                    facts.add(new Fact(validatedText(fact.get("text")), MemoryEntry.cleanTags(tags),
                            optionalString(fact.get("replace_id"), "replace_id")));
                } else {
                    throw new IllegalArgumentException("Each fact must be a string or an object with text");
                }
            }
            return facts;
        }
        if (hasText && hasMemory) {
            throw new IllegalArgumentException("Supply text or its memory alias, not both");
        }
        return List.of(new Fact(validatedText(hasText ? args.get("text") : args.get("memory")),
                sharedTags, optionalString(args.get("replace_id"), "replace_id")));
    }

    private static String validatedText(Object value) {
        if (!(value instanceof String text)) {
            throw new IllegalArgumentException("text must be a string");
        }
        return MemoryService.validateText(text);
    }

    private static String optionalString(Object value, String field) {
        if (value != null && !(value instanceof String)) {
            throw new IllegalArgumentException(field + " must be a string");
        }
        return ToolArguments.omitted(value) ? "" : str(value);
    }

    private static List<String> validatedTags(Object value) {
        if (ToolArguments.omitted(value)) {
            return List.of();
        }
        if (value != null && !(value instanceof String) && !(value instanceof List<?>)) {
            throw new IllegalArgumentException("tags must be a string or an array of strings");
        }
        if (value instanceof List<?> list && list.stream().anyMatch(item -> !(item instanceof String))) {
            throw new IllegalArgumentException("tags must contain strings only");
        }
        return tags(value);
    }

    static String str(Object value) {
        return value == null ? "" : String.valueOf(value).strip();
    }

    /** Accepts a JSON list or a comma separated string. */
    static List<String> tags(Object value) {
        if (value instanceof Collection<?> list) {
            List<String> items = new ArrayList<>();
            list.forEach(item -> items.add(item == null ? null : String.valueOf(item)));
            return MemoryEntry.cleanTags(items);
        }
        return value == null ? List.of() : MemoryEntry.parseTags(String.valueOf(value));
    }
}
