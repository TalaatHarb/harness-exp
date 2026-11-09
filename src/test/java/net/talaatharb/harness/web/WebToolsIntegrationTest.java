package net.talaatharb.harness.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import net.talaatharb.harness.agent.ConversationScope;
import net.talaatharb.harness.config.HarnessSettings;
import net.talaatharb.harness.rag.HashingEmbedder;
import net.talaatharb.harness.rag.RagDocument;
import net.talaatharb.harness.rag.RagService;
import net.talaatharb.harness.rag.RagStore;
import net.talaatharb.harness.tools.ToolResult;

/** Both tools end to end with a fake provider and a fake page fetcher (no network). */
class WebToolsIntegrationTest {
    @TempDir
    Path dir;
    private final HarnessSettings settings = new HarnessSettings();
    private final List<String> fetched = new ArrayList<>();
    private final List<String> queries = new ArrayList<>();
    private final Map<String, String> pages = new HashMap<>();
    private RagStore store;

    private static String page(String title, String body) {
        return "<html><head><title>" + title + "</title><meta property=\"article:published_time\" "
                + "content=\"2025-03-04\"></head><body><article><p>" + body + "</p></article></body></html>";
    }

    private final SearchProvider provider = new SearchProvider() {
        @Override
        public String id() {
            return "fake";
        }

        @Override
        public String displayName() {
            return "Fake";
        }

        @Override
        public List<SearchResult> search(String query, int limit, Map<String, String> config, WebSearchSettings s) {
            queries.add(query + "|" + config.getOrDefault("k", ""));
            return List.of(new SearchResult("Alpha", "https://a.example/alpha", "alpha snippet", "fake"),
                    new SearchResult("Alpha mirror", "https://www.a.example/alpha/?utm_source=x", "", "fake"),
                    new SearchResult("Beta", "https://b.example/beta", "beta snippet", "fake"),
                    new SearchResult("Broken", "https://c.example/broken", "", "fake"),
                    new SearchResult("Beta copy", "https://d.example/beta-copy", "", "fake"),
                    new SearchResult("Gamma", "https://e.example/gamma", "", "fake"));
        }
    };

    private final PageFetcher fetcher = (url, s) -> {
        fetched.add(url);
        String html = pages.get(url);
        if (html == null) {
            throw new IOException("Blocked by robots.txt: " + url);
        }
        return new PageFetcher.FetchedPage(url, html, "text/html");
    };

    private final List<AutoCloseable> open = new ArrayList<>();

    @AfterEach
    void close() throws Exception {
        for (AutoCloseable c : open) {
            c.close();
        }
    }

    private WebResearchService research() {
        settings.tools.web.provider = "fake";
        settings.tools.web.providerConfig.put("fake", Map.of("k", "v"));
        settings.tools.web.minContentChars = 20;
        pages.put("https://a.example/alpha", page("Alpha page", "Alpha facts about quantum computers and qubits "
                + "that are interesting enough to be returned to the agent."));
        pages.put("https://b.example/beta", page("Beta page", "Beta explains databases and indexes thoroughly "
                + "with a long enough paragraph to pass the readability filter."));
        pages.put("https://d.example/beta-copy", page("Beta page copy", "Beta explains databases and indexes "
                + "thoroughly with a long enough paragraph to pass the readability filter."));
        pages.put("https://e.example/gamma", page("Gamma page", "Gamma discusses compilers and parsers in a "
                + "paragraph that is also long enough to be extracted properly."));
        return new WebResearchService(() -> settings, new SearchProviderRegistry(Set.of(provider)), fetcher,
                new JsoupContentExtractor());
    }

    private RagService rag() {
        store = new RagStore(dir.resolve("rag"));
        open.add(store);
        return new RagService(() -> settings, store, s -> new HashingEmbedder());
    }

    @Test
    void placeholderLimitsUseConfiguredDefaultAndInvalidInputsDoNotSearch() throws Exception {
        var extract = new WebSearchExtractTool(research());
        assertTrue(extract.execute(Map.of("query", "quantum", "maxPages", "default")).success());
        int count = queries.size();
        assertFalse(extract.execute(Map.of("query", Map.of())).success());
        assertFalse(extract.execute(Map.of("query", "q", "maxPages", "NaN")).success());
        assertEquals(count, queries.size());
    }

    @Test
    void extractToolReturnsContentWithoutPersisting() throws Exception {
        RagService rag = rag();
        WebSearchExtractTool tool = new WebSearchExtractTool(research());
        ToolResult result = tool.execute(Map.of("query", "quantum", "maxPages", 3));
        assertTrue(result.success(), result.output());
        String out = result.output();
        assertEquals(List.of("quantum|v"), queries, "configured provider and its config are used");
        assertTrue(out.contains("## Search results"));
        assertTrue(out.contains("Alpha facts about quantum"));
        assertTrue(out.contains("Source: https://a.example/alpha"));
        assertTrue(out.contains("Published: 2025-03-04"));
        assertTrue(out.contains("Beta explains databases"));
        assertTrue(out.contains("Gamma discusses"), out);
        assertTrue(out.contains("Failed:") && out.contains("robots.txt"), out);
        assertTrue(out.contains("duplicate of"), out);
        assertFalse(fetched.contains("https://www.a.example/alpha/?utm_source=x"), "URL duplicate not fetched twice");
        assertTrue(rag.documents("c1").isEmpty());
        assertTrue(rag.store().toString().length() > 0);
    }

    @Test
    void extractToolEnforcesTotalCharBudgetAndValidatesInput() throws Exception {
        WebResearchService research = research();
        settings.tools.web.maxCharsPerPage = 200;
        settings.tools.web.maxTotalChars = 200;
        ToolResult limited = new WebSearchExtractTool(research).execute(Map.of("query", "q", "maxPages", "2"));
        assertTrue(limited.success(), limited.output());
        assertTrue(limited.output().contains("content limit") || limited.output().length() < 2_000);
        assertFalse(new WebSearchExtractTool(research).execute(Map.of()).success());
    }

    @Test
    void extractToolReportsProviderErrors() throws Exception {
        SearchProvider failing = new SearchProvider() {
            @Override
            public String id() {
                return "fake";
            }

            @Test
            void extractToolDoesNotClaimSuccessWhenAllPagesFailOrHaveNoContent() throws Exception {
                WebSearchExtractTool tool = new WebSearchExtractTool(research());
                pages.clear();
                ToolResult failed = tool.execute(Map.of("query", "q"));
                assertFalse(failed.success(), failed.output());
                assertTrue(failed.output().contains("## Search results"));
                assertTrue(failed.output().contains("Failed:"));
                pages.put("https://a.example/alpha", "<html><body><p>Short</p></body></html>");
                ToolResult empty = tool.execute(Map.of("query", "q"));
                assertFalse(empty.success(), empty.output());
                assertTrue(empty.output().contains("no readable content"));
            }

            @Override
            public String displayName() {
                return "Failing";
            }

            @Override
            public List<SearchResult> search(String q, int l, Map<String, String> c, WebSearchSettings s)
                    throws IOException {
                throw new IOException("rate limited");
            }
        };
        settings.tools.web.provider = "fake";
        WebResearchService svc = new WebResearchService(() -> settings, new SearchProviderRegistry(Set.of(failing)),
                fetcher, new JsoupContentExtractor());
        ToolResult r = new WebSearchExtractTool(svc).execute(Map.of("query", "x"));
        assertFalse(r.success());
        assertTrue(r.output().contains("rate limited"));
        settings.tools.web.provider = "nope";
        assertFalse(new WebSearchExtractTool(svc).execute(Map.of("query", "x")).success());
    }

    @Test
    void indexToolAddsDocumentsToRagWithSourceMetadataAndSkipsDuplicates() throws Exception {
        RagService rag = rag();
        WebSearchIndexTool tool = new WebSearchIndexTool(research(), rag);
        assertFalse(tool.execute(Map.of("query", "q")).success(), "needs an open conversation");

        String previous = ConversationScope.enter("c1");
        try {
            ToolResult first = tool.execute(Map.of("query", "databases", "maxPages", 2));
            assertTrue(first.success(), first.output());
            assertTrue(first.output().contains("Indexed 2"), first.output());
            List<RagDocument> docs = rag.documents("c1");
            assertEquals(2, docs.size());
            assertTrue(docs.stream().anyMatch(d -> d.name.equals("Alpha page")
                    && d.source.equals("https://a.example/alpha") && d.chunks > 0));
            assertTrue(docs.stream().anyMatch(d -> d.name.equals("Beta page")
                    && d.source.equals("https://b.example/beta")));
            var hits = rag.search("c1", "databases and indexes", 3, 0).hits();
            assertFalse(hits.isEmpty());
            assertTrue(hits.get(0).text().contains("Source: https://"), hits.get(0).text());

            fetched.clear();
            ToolResult second = tool.execute(Map.of("query", "databases", "maxPages", 3));
            assertTrue(second.success(), second.output());
            assertTrue(second.output().contains("already indexed"), second.output());
            assertTrue(second.output().contains("Indexed 1"), "only the new page: " + second.output());
            assertTrue(second.output().contains("duplicate of already indexed"), second.output());
            assertFalse(fetched.contains("https://a.example/alpha"), "previously indexed pages are not refetched");
            assertEquals(3, rag.documents("c1").size());
        } finally {
            ConversationScope.restore(previous);
        }
    }

    @Test
    void indexToolReportsFailuresWhenNothingCouldBeIndexed() throws Exception {
        RagService rag = rag();
        WebResearchService research = research();
        pages.clear();
        String previous = ConversationScope.enter("c2");
        try {
            ToolResult r = new WebSearchIndexTool(research, rag).execute(Map.of("query", "x"));
            assertFalse(r.success());
            assertTrue(r.output().contains("Indexed 0") && r.output().contains("Failed:"), r.output());
            assertTrue(rag.documents("c2").isEmpty());
        } finally {
            ConversationScope.restore(previous);
        }
    }

    @Test
    void toolsAreSensitive() {
        WebResearchService research = research();
        assertEquals(net.talaatharb.harness.tools.RiskLevel.SENSITIVE, new WebSearchExtractTool(research).risk());
        assertEquals(net.talaatharb.harness.tools.RiskLevel.SENSITIVE, new WebSearchIndexTool(research, rag()).risk());
    }
}
