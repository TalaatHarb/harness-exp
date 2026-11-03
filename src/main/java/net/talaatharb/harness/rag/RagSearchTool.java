package net.talaatharb.harness.rag;

import java.util.List;
import java.util.Map;

import com.google.inject.Inject;

import net.talaatharb.harness.agent.ConversationScope;
import net.talaatharb.harness.tools.Tool;
import net.talaatharb.harness.tools.ToolResult;

/** Vector search over the current conversation's documents. */
public class RagSearchTool implements Tool {
    public static final String NAME = "rag_search";

    private final RagService rag;

    @Inject
    public RagSearchTool(RagService rag) {
        this.rag = rag;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "Searches the documents added to this conversation's knowledge base (files, PDFs, long texts) and "
                + "returns the most relevant passages with their document names, best first. Describe what you are "
                + "looking for in a sentence or a few keywords. An empty query lists the documents.";
    }

    @Override
    public String argumentsHint() {
        return "{\"query\": \"refund policy for damaged items\", \"limit\": 5}";
    }

    @Override
    public ToolResult execute(Map<String, Object> arguments) throws Exception {
        Map<String, Object> args = arguments == null ? Map.of() : arguments;
        String conversation = ConversationScope.current();
        if (conversation == null) {
            return ToolResult.error("No conversation is open, so there are no documents to search.");
        }
        RagSettings settings = rag.settings();
        int max = Math.max(1, settings.maxSearchResults);
        int limit = max;
        Object requested = args.get("limit");
        if (requested != null) {
            try {
                limit = Math.max(1, Math.min(max, (int) Double.parseDouble(String.valueOf(requested))));
            } catch (NumberFormatException e) {
                return ToolResult.error("limit must be a number");
            }
        }
        String query = args.get("query") == null ? "" : String.valueOf(args.get("query")).strip();
        List<RagDocument> docs = rag.documents(conversation);
        if (docs.isEmpty()) {
            return ToolResult.ok("This conversation has no documents. The user can add files with the 📚 button in "
                    + "the chat window, or you can add text with " + RagIngestTool.NAME + ".");
        }
        if (query.isEmpty()) {
            StringBuilder sb = new StringBuilder(docs.size() + " document(s):");
            docs.forEach(d -> sb.append("\n- ").append(d.name).append(" (").append(d.chunks).append(" parts, ")
                    .append(d.chars).append(" chars)"));
            return ToolResult.ok(sb.toString());
        }
        RagService.SearchResult result = rag.search(conversation, query, limit, settings.minScorePercent / 100.0);
        StringBuilder sb = new StringBuilder();
        if (result.hits().isEmpty()) {
            sb.append("No passages match \"").append(query).append("\" in ").append(result.documents())
                    .append(" document(s). Try other words.");
        } else {
            sb.append(result.hits().size()).append(" passage(s), best first:");
            int i = 1;
            for (RagService.Hit hit : result.hits()) {
                sb.append("\n\n[").append(i++).append("] ").append(RagService.label(hit))
                        .append(String.format(" (score %.2f)%n", hit.score())).append(hit.text());
            }
        }
        if (!result.otherEmbedder().isEmpty()) {
            sb.append("\n\nNot searched (indexed with another embedding model; the user can re-index them): ")
                    .append(String.join(", ", result.otherEmbedder().stream().map(d -> d.name).toList()));
        }
        return ToolResult.ok(sb.toString());
    }
}
