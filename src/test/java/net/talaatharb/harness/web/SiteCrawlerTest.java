package net.talaatharb.harness.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import net.talaatharb.harness.config.HarnessSettings;

class SiteCrawlerTest {
    private final Map<String, String> pages = new HashMap<>();
    private final Map<String, String> redirects = new HashMap<>();
    private final List<String> fetched = new ArrayList<>();

    private final PageFetcher fetcher = (url, s) -> {
        fetched.add(url);
        String target = redirects.getOrDefault(url, url);
        String html = pages.get(target);
        if (html == null) {
            throw new IOException("HTTP 404 from " + url);
        }
        return new PageFetcher.FetchedPage(target, html, "text/html");
    };

    private static String page(String title, String text, String... links) {
        StringBuilder sb = new StringBuilder("<html><head><title>").append(title).append("</title></head><body><main><p>")
                .append(text).append(" This paragraph is long enough to count as readable content.</p>");
        for (String link : links) {
            sb.append("<a href=\"").append(link).append("\">link</a>");
        }
        return sb.append("</main></body></html>").toString();
    }

    private SiteCrawler crawler() {
        return new SiteCrawler(HarnessSettings::new, fetcher, new JsoupContentExtractor());
    }

    private List<String> crawl(String url, int depth, int max, Set<String> skip) throws IOException {
        List<String> accepted = new ArrayList<>();
        crawler().crawl(url, depth, max, skip, p -> accepted.add(p.document().url() + "@" + p.depth()), null);
        return accepted;
    }

    @Test
    void followsSameHostLinksUpToDepthAndIgnoresOtherHosts() throws Exception {
        pages.put("https://site.test/", page("Home", "Home page", "/a", "https://www.site.test/b#top",
                "https://other.test/x", "/file.pdf", "mailto:me@site.test", "/a"));
        pages.put("https://site.test/a", page("A", "Page A", "/c"));
        pages.put("https://www.site.test/b", page("B", "Page B"));
        pages.put("https://site.test/c", page("C", "Page C", "/d"));
        pages.put("https://site.test/d", page("D", "Page D"));

        assertEquals(List.of("https://site.test/@0"), crawl("site.test/", 0, 10, Set.of()));

        fetched.clear();
        List<String> depth2 = crawl("https://site.test/", 2, 10, Set.of());
        assertEquals(List.of("https://site.test/@0", "https://site.test/a@1", "https://www.site.test/b@1",
                "https://site.test/c@2"), depth2);
        assertTrue(fetched.stream().noneMatch(u -> u.contains("other.test") || u.endsWith(".pdf")), fetched::toString);
    }

    @Test
    void respectsPageLimitSkipsKnownPagesAndOffSiteRedirects() throws Exception {
        pages.put("https://site.test/", page("Home", "Home page", "/a", "/b", "/out"));
        pages.put("https://site.test/a", page("A", "Page A"));
        pages.put("https://site.test/b", page("B", "Page B"));
        pages.put("https://elsewhere.test/", page("Elsewhere", "Another site"));
        redirects.put("https://site.test/out", "https://elsewhere.test/");

        assertEquals(2, crawl("https://site.test/", 1, 2, Set.of()).size());

        List<String> accepted = new ArrayList<>();
        SiteCrawler.Result r = crawler().crawl("https://site.test/", 1, 10,
                Set.of(DocumentDeduplicator.normalizeUrl("https://site.test/a")), p -> accepted.add(p.document().url()),
                null);
        assertEquals(List.of("https://site.test/", "https://site.test/b"), accepted);
        assertTrue(r.skipped().stream().anyMatch(s -> s.reason().equals("already indexed")));
        assertTrue(r.skipped().stream().anyMatch(s -> s.reason().startsWith("redirected outside the site")));
    }

    @Test
    void rejectsInvalidStartUrls() {
        assertThrows(IOException.class, () -> crawl(" ", 1, 5, Set.of()));
        assertThrows(IOException.class, () -> crawl("ftp://site.test/", 1, 5, Set.of()));
        assertThrows(IOException.class, () -> crawl("https://user:pw@site.test/", 1, 5, Set.of()));
    }
}
