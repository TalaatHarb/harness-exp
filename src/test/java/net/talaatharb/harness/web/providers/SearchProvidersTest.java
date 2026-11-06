package net.talaatharb.harness.web.providers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.util.List;

import org.junit.jupiter.api.Test;

import net.talaatharb.harness.web.SearchResult;

class SearchProvidersTest {

    @Test
    void searxngResultsAreNormalized() throws IOException {
        String json = "{\"results\":[{\"title\":\" One \",\"url\":\"https://a.org/1\",\"content\":\"snip\","
                + "\"publishedDate\":\"2024-05-01T00:00:00\",\"engine\":\"bing\"},{\"title\":\"no url\"},"
                + "{\"title\":\"Two\",\"url\":\"https://b.org\"}]}";
        List<SearchResult> r = SearxngSearchProvider.parse(json, 5);
        assertEquals(2, r.size());
        assertEquals("One", r.get(0).title());
        assertEquals("snip", r.get(0).snippet());
        assertEquals("2024-05-01T00:00:00", r.get(0).publishedDate());
        assertEquals("bing", r.get(0).metadata().get("engine"));
        assertEquals("searxng", r.get(0).provider());
        assertEquals("", r.get(1).snippet());
        assertEquals(1, SearxngSearchProvider.parse(json, 1).size());
        assertThrows(IOException.class, () -> SearxngSearchProvider.parse("<html>not json</html>", 5));
    }

    @Test
    void duckDuckGoResultsAreUnwrappedAndAdsSkipped() {
        String html = """
                <div class="result result--ad"><a class="result__a" href="https://ad.example/">Ad</a></div>
                <div class="result"><h2><a class="result__a" href="//duckduckgo.com/l/?uddg=https%3A%2F%2Fexample.com%2Fp%3Fa%3D1&rut=x">Example &amp; Co</a></h2>
                <a class="result__snippet">A snippet</a></div>
                <div class="result"><a class="result__a" href="https://direct.org/">Direct</a></div>""";
        List<SearchResult> r = DuckDuckGoSearchProvider.parse(html, 10);
        assertEquals(2, r.size());
        assertEquals("https://example.com/p?a=1", r.get(0).url());
        assertEquals("Example & Co", r.get(0).title());
        assertEquals("A snippet", r.get(0).snippet());
        assertEquals("duckduckgo", r.get(0).provider());
        assertEquals("https://direct.org/", r.get(1).url());
    }

    @Test
    void wikipediaResultsAreNormalized() throws IOException {
        String json = "{\"query\":{\"search\":[{\"title\":\"Java (programming language)\","
                + "\"snippet\":\"a <span class=\\\"searchmatch\\\">Java</span> language\","
                + "\"timestamp\":\"2025-01-01T00:00:00Z\",\"wordcount\":10}]}}";
        List<SearchResult> r = WikipediaSearchProvider.parse(json, "en", 5);
        assertEquals(1, r.size());
        assertEquals("https://en.wikipedia.org/wiki/Java_%28programming_language%29", r.get(0).url());
        assertEquals("a Java language", r.get(0).snippet());
        assertEquals("2025-01-01T00:00:00Z", r.get(0).publishedDate());
    }

    @Test
    void providersExposeStableIdsAndConfig() {
        assertEquals("duckduckgo", new DuckDuckGoSearchProvider().id());
        assertEquals("searxng", new SearxngSearchProvider().id());
        assertEquals("wikipedia", new WikipediaSearchProvider().id());
        assertFalse(new SearxngSearchProvider().configFields().isEmpty());
    }
}
