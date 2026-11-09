package net.talaatharb.harness.web;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.google.inject.Inject;

import net.talaatharb.harness.agent.ConversationScope;
import net.talaatharb.harness.rag.RagDocument;
import net.talaatharb.harness.rag.RagSearchTool;
import net.talaatharb.harness.rag.RagService;
import net.talaatharb.harness.tools.RiskLevel;
import net.talaatharb.harness.tools.Tool;
import net.talaatharb.harness.tools.ToolResult;

/**
 * Searches the web and adds the extracted pages to the conversation's RAG knowledge base through
 * {@link RagService#ingestText} (chunking, embedding, redaction and persistence are RAG's own).
 */
public class WebSearchIndexTool implements Tool {
    public static final String NAME = "web_search_index";

    private final WebResearchService research;
    private final RagService rag;

    @Inject
    public WebSearchIndexTool(WebResearchService research, RagService rag) {
        this.research = research;
        this.rag = rag;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "Searches the web with the configured search provider, fetches the most relevant pages and adds their "
                + "text to this conversation's knowledge base (source URL and title are kept; pages already indexed "
                + "are skipped). Returns an indexing summary; read the content afterwards with " + RagSearchTool.NAME
                + ". Use " + WebSearchExtractTool.NAME + " when you only need the text now.";
    }

    @Override
    public String argumentsHint() {
        return "{\"query\": \"Spring Boot 3 migration guide\", \"maxPages\": 3}  (maxPages optional)";
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
        String query;
        int pages;
        WebSearchSettings web = research.settings();
        try {
            query = WebToolArgs.query(args);
            pages = WebToolArgs.maxPages(args, web.maxPages);
        } catch (IllegalArgumentException e) {
            return ToolResult.error(e.getMessage());
        }
        if (query.isEmpty()) {
            return ToolResult.error("Give a non-empty 'query'.");
        }
        Set<String> indexed = new HashSet<>();
        DocumentDeduplicator known = new DocumentDeduplicator();
        for (RagDocument d : rag.documents(conversation)) {
            indexed.add(DocumentDeduplicator.normalizeUrl(d.source));
            if (d.source != null && d.source.startsWith("http")) {
                known.addContent(d.source, storedBody(conversation, d));
            }
        }
        WebResearchService.Research r;
        try {
            r = research.research(query, pages, web.maxIndexCharsPerPage,
                    url -> indexed.contains(DocumentDeduplicator.normalizeUrl(url)) ? "already indexed" : null);
        } catch (IOException e) {
            return ToolResult.error("Web search failed: " + e.getMessage());
        }

        List<String> added = new ArrayList<>();
        List<WebResearchService.Skipped> failures = new ArrayList<>(r.failures());
        List<WebResearchService.Skipped> skipped = new ArrayList<>(r.skipped());
        for (ExtractedDocument d : r.documents()) {
            String same = known.addContent(d.url(), d.content());
            if (same != null) {
                skipped.add(new WebResearchService.Skipped(d.url(), "duplicate of already indexed " + same));
                continue;
            }
            try {
                RagDocument doc = rag.ingestText(conversation, d.title().isBlank() ? d.url() : d.title(), d.url(),
                        withHeader(d), null);
                added.add("\"" + doc.name + "\" — " + d.url() + " (" + doc.chars + " chars, " + doc.chunks + " parts)");
            } catch (IOException | RuntimeException e) {
                failures.add(new WebResearchService.Skipped(d.url(), e.getMessage() == null ? e.toString() : e.getMessage()));
            }
        }

        StringBuilder sb = new StringBuilder();
        sb.append("Web search \"").append(query).append("\" via ").append(r.provider()).append(": ")
                .append(r.results().size()).append(" results. Indexed ").append(added.size()).append(", skipped ")
                .append(skipped.size()).append(", failed ").append(failures.size()).append(".");
        if (!added.isEmpty()) {
            sb.append("\n\nIndexed:\n");
            added.forEach(a -> sb.append("- ").append(a).append('\n'));
            sb.append("Search them with ").append(RagSearchTool.NAME).append('.');
        }
        WebToolArgs.appendProblems(sb, "Skipped", skipped);
        WebToolArgs.appendProblems(sb, "Failed", failures);
        return added.isEmpty() && !failures.isEmpty() ? ToolResult.error(sb.toString().strip())
                : ToolResult.ok(sb.toString().strip());
    }

    /** The stored text of an indexed page without the header added by {@link #withHeader}. */
    private String storedBody(String conversation, RagDocument d) {
        StringBuilder sb = new StringBuilder();
        for (var chunk : rag.store().chunks(conversation, d.id)) {
            sb.append(chunk.text()).append('\n');
        }
        String text = sb.toString();
        int blank = text.indexOf("\n\n");
        return text.startsWith("Title: ") || text.startsWith("Source: ") ? (blank < 0 ? "" : text.substring(blank + 2)) : text;
    }

    /** RAG stores name and source itself; the date is not a RAG field, so it travels as a header line in the text. */
    static String withHeader(ExtractedDocument d) {
        StringBuilder sb = new StringBuilder();
        if (!d.title().isBlank()) {
            sb.append("Title: ").append(d.title()).append('\n');
        }
        sb.append("Source: ").append(d.url()).append('\n');
        if (!d.publishedDate().isBlank()) {
            sb.append("Published: ").append(d.publishedDate()).append('\n');
        }
        return sb.append('\n').append(d.content()).toString();
    }
}
