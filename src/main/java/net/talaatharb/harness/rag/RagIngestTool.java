package net.talaatharb.harness.rag;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import com.google.inject.Inject;

import net.talaatharb.harness.agent.ConversationScope;
import net.talaatharb.harness.tools.RiskLevel;
import net.talaatharb.harness.tools.Tool;
import net.talaatharb.harness.tools.ToolArguments;
import net.talaatharb.harness.tools.ToolResult;

/** Adds text or a local file to the current conversation's knowledge base. Sensitive: it can read local files. */
public class RagIngestTool implements Tool {
    public static final String NAME = "rag_add";

    private final RagService rag;

    @Inject
    public RagIngestTool(RagService rag) {
        this.rag = rag;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "Adds a large text, or a local markdown/text/PDF file, to this conversation's knowledge base so it "
                + "can be searched later with " + RagSearchTool.NAME + ". Use it for material too long to keep in "
                + "the chat (documents, notes, tool output), not for short facts (use remember for those). Give "
                + "either text (with a name) or path.";
    }

    @Override
    public String argumentsHint() {
        return "{\"name\": \"meeting notes 2024-05\", \"text\": \"...\"} or {\"path\": \"C:/docs/handbook.pdf\"}";
    }

    @Override
    public RiskLevel risk() {
        return RiskLevel.SENSITIVE;
    }

    @Override
    public ToolResult execute(Map<String, Object> arguments) throws Exception {
        Map<String, Object> args = arguments == null ? Map.of() : arguments;
        String conversation = ConversationScope.current();
        if (conversation == null) {
            return ToolResult.error("No conversation is open.");
        }
        RagDocument doc;
        try {
            Object rawText = args.get("text");
            if (rawText != null && !(rawText instanceof String)) {
                return ToolResult.error("'text' must be a string");
            }
            String text = rawText == null ? "" : (String) rawText;
            String path = ToolArguments.string(args, "path", "").strip();
            String name = ToolArguments.string(args, "name", "").strip();
            if (!path.isEmpty() && !text.isBlank()) {
                return ToolResult.error("Supply either 'path' or 'text', not both");
            }
            if (!path.isEmpty()) {
                Path file = Path.of(path).toAbsolutePath().normalize();
                if (!Files.isRegularFile(file)) {
                    return ToolResult.error("Not a file: " + file);
                }
                doc = rag.ingestFile(conversation, file, name, null);
            } else if (!text.isBlank()) {
                doc = rag.ingestText(conversation, name.isEmpty() ? "Agent notes" : name, "agent", text, null);
            } else {
                return ToolResult.error("Give 'text' (and a 'name') or a file 'path'.");
            }
        } catch (IllegalArgumentException e) {
            return ToolResult.error(e.getMessage());
        }
        return ToolResult.ok("Added \"" + doc.name + "\" (" + doc.chars + " chars, " + doc.chunks
                + " parts) to this conversation's documents. Search it with " + RagSearchTool.NAME + ".");
    }
}
