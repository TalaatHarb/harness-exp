package net.talaatharb.harness.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import org.junit.jupiter.api.Test;

class WebUnitTest {
    private static final String PAGE = """
            <html><head><title>Fallback title</title>
            <meta property="og:title" content="Java 25 released">
            <meta property="article:published_time" content="2025-09-16T10:00:00Z">
            <meta property="og:site_name" content="Example News">
            <script>var tracking = 'Do not show this script text at all in output';</script></head>
            <body>
            <nav><ul><li><a href="/">Home page navigation link number one</a></li></ul></nav>
            <div class="cookie-banner"><p>We use cookies to improve your experience on this website, accept?</p></div>
            <article>
              <h1>Java 25 released</h1>
              <p>Java 25 is the newest long term support release of the platform and ships with many improvements.</p>
              <p>Java 25 is the newest long term support release of the platform and ships with many improvements.</p>
              <p>Flexible constructor bodies, compact source files and module imports are now final features.</p>
              <ul><li>Scoped values make sharing immutable data between threads simpler than before.</li></ul>
              <pre>System.out.println("hello");</pre>
              <p>Short.</p>
            </article>
            <footer><p>Copyright notice that should not be part of the extracted article text.</p></footer>
            </body></html>""";

    @Test
    void extractorKeepsArticleTextAndMetadataAndDropsChrome() {
        ExtractedDocument d = new JsoupContentExtractor().extract("https://example.com/a", PAGE, 10_000);
        assertEquals("Java 25 released", d.title());
        assertEquals("2025-09-16T10:00:00Z", d.publishedDate());
        assertEquals("Example News", d.siteName());
        assertTrue(d.content().contains("long term support release"));
        assertTrue(d.content().contains("Flexible constructor bodies"));
        assertTrue(d.content().contains("Scoped values"));
        assertTrue(d.content().contains("System.out.println"));
        assertFalse(d.content().contains("navigation"));
        assertFalse(d.content().contains("cookies"));
        assertFalse(d.content().contains("Copyright"));
        assertFalse(d.content().contains("tracking"));
        assertFalse(d.content().contains("Short."));
        assertEquals(1, d.content().split("newest long term support", -1).length - 1, "repeated paragraph removed");
    }

    @Test
    void extractorFindsJsonLdDateAndTruncates() {
        String html = "<html><head><script type=\"application/ld+json\">{\"@graph\":[{\"datePublished\":\"2024-01-02\"}]}"
                + "</script></head><body><main>" + "<p>Sentence about something useful and long enough to keep. </p>".repeat(40)
                + "</main></body></html>";
        ExtractedDocument d = new JsoupContentExtractor().extract("https://x.org", html, 300);
        assertEquals("2024-01-02", d.publishedDate());
        assertTrue(d.content().length() <= 305, "" + d.content().length());
    }

    @Test
    void extractorHandlesEmptyHtml() {
        assertEquals("", new JsoupContentExtractor().extract("https://x.org", "", 1000).content());
        assertEquals("", new JsoupContentExtractor().extract("https://x.org", null, 1000).content());
    }

    @Test
    void wikipediaPageWideFeatureFlagsDoNotDeleteTheArticle() {
        String text = "The Pythagorean theorem describes the sides of a right triangle. ".repeat(6);
        String html = "<html class='vector-feature-main-menu-pinned-disabled'>"
                + "<body class='sidebar-disabled'><div class='sidebar'><p>Noise to remove from the page.</p></div>"
                + "<main><h1>Pythagorean theorem</h1><p>" + text + "</p></main></body></html>";
        ExtractedDocument extracted = new JsoupContentExtractor().extract("https://en.wikipedia.org/wiki/Test",
                html, 2500);
        assertTrue(extracted.content().contains(text.strip()), extracted.content());
        assertFalse(extracted.content().contains("Noise"));
    }

    @Test
    void urlNormalizationIgnoresTrackingAndCosmetics() {
        String a = DocumentDeduplicator.normalizeUrl("http://www.Example.com/path/?utm_source=x&b=2&a=1#frag");
        String b = DocumentDeduplicator.normalizeUrl("https://example.com:443/path?a=1&b=2");
        assertEquals(a, b);
        assertFalse(DocumentDeduplicator.normalizeUrl("https://example.com/other").equals(b));
    }

    @Test
    void deduplicatorFindsUrlExactAndNearDuplicates() {
        DocumentDeduplicator d = new DocumentDeduplicator();
        assertNull(d.addUrl("https://example.com/a"));
        assertNotNull(d.addUrl("https://www.example.com/a/?utm_campaign=z"));

        String text = "The quick brown fox jumps over the lazy dog while the rocket engines burn fuel at night. "
                .repeat(5);
        assertNull(d.addContent("https://one.org", text));
        assertEquals("https://one.org", d.addContent("https://two.org", text.toUpperCase()));
        assertEquals("https://one.org", d.addContent("https://three.org", text + " One extra closing sentence."));
        assertNull(d.addContent("https://four.org", "A completely different article about cooking pasta in "
                + "salted boiling water for exactly eight minutes before draining and serving it hot."));
    }

    @Test
    void robotsTxtRulesFollowSpecificityAndAgentGroups() {
        String robots = """
                User-agent: *
                Disallow: /private
                Allow: /private/public
                Disallow: /*.pdf$

                User-agent: HarnessBot
                Disallow: /secret
                """;
        RobotsTxt general = RobotsTxt.parse(robots, "OtherBot");
        assertFalse(general.isAllowed("/private/x"));
        assertTrue(general.isAllowed("/private/public/x"));
        assertFalse(general.isAllowed("/a/file.pdf"));
        assertTrue(general.isAllowed("/a/file.pdf?x=1"));
        RobotsTxt mine = RobotsTxt.parse(robots, "HarnessBot");
        assertTrue(mine.isAllowed("/private/x"));
        assertFalse(mine.isAllowed("/secret/a"));
        assertTrue(RobotsTxt.ALLOW_ALL.isAllowed("/x"));
        assertFalse(RobotsTxt.DISALLOW_ALL.isAllowed("/x"));
    }

    @Test
    void settingsAreClampedAndFallBackToDefaults() {
        WebSearchSettings s = new WebSearchSettings();
        s.maxPages = 999;
        s.maxResults = 0;
        s.provider = " ";
        s.providerConfig = null;
        s.sanitized();
        assertEquals(10, s.maxPages);
        assertEquals(1, s.maxResults);
        assertEquals(WebSearchSettings.DEFAULT_PROVIDER, s.provider);
        assertTrue(s.configFor("x").isEmpty());
    }

    @Test
    void searchResultNormalizesNulls() {
        SearchResult r = new SearchResult(null, " https://a.org ", null, null, null, null);
        assertEquals("", r.title());
        assertEquals("https://a.org", r.url());
        assertEquals(Map.of(), r.metadata());
    }
}
