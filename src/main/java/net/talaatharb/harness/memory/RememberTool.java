package net.talaatharb.harness.memory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.inject.Inject;

import net.talaatharb.harness.tools.RiskLevel;
import net.talaatharb.harness.tools.Tool;
import net.talaatharb.harness.tools.ToolResult;

/** Saves a fact to long-term memory (markdown folder or MapDB, as configured), skipping near-duplicates. */
public class RememberTool implements Tool {
    public static final String NAME = "remember";
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
        return "Saves a durable fact, preference or decision to long-term memory so it is available in later "
                + "conversations. Store one short, self-contained fact per call, written so it makes sense on its own "
                + "(e.g. \"The user's preferred language is Python\"). Memories that repeat an existing one are not "
                + "added again. To correct or update a memory, pass its id (from recall or the Memory section of "
                + "your context) as replace_id. Never store passwords, keys or other secrets.";
    }

    @Override
    public String argumentsHint() {
        return "{\"text\": \"The user prefers metric units\", \"tags\": [\"preferences\"], "
                + "\"replace_id\": \"(optional) id of the memory this replaces\"}";
    }

    /** Memories end up in every later prompt, so writes are sensitive (approval per the policy). */
    @Override
    public RiskLevel risk() {
        return RiskLevel.SENSITIVE;
    }

    @Override
    public ToolResult execute(Map<String, Object> arguments) {
        Map<String, Object> args = arguments == null ? Map.of() : arguments;
        String text = str(args.get("text"));
        if (text.isBlank()) {
            text = str(args.get("memory"));
        }
        try {
            MemoryService.RememberResult r = memory.remember(text, tags(args.get("tags")), str(args.get("replace_id")),
                    MemoryEntry.SOURCE_AGENT);
            return ToolResult.ok(switch (r.outcome()) {
                case ADDED -> "Remembered as [" + r.entry().id + "].";
                case UPDATED -> "Updated memory [" + r.entry().id + "] (was: \"" + r.previous().preview(200)
                        + "\").";
                case DUPLICATE -> "Already remembered as [" + r.entry().id + "]: \"" + r.entry().preview(200)
                        + "\". Nothing added. Pass replace_id to change it.";
            });
        } catch (IllegalArgumentException e) {
            return ToolResult.error(e.getMessage());
        } catch (IOException | RuntimeException e) {
            LOG.error("remember failed", e);
            return ToolResult.error("Could not save the memory: " + e.getMessage());
        }
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
