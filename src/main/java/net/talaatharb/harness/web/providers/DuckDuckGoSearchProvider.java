package net.talaatharb.harness.web.providers;

import java.io.IOException;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import net.talaatharb.harness.web.SearchProvider;
import net.talaatharb.harness.web.SearchResult;
import net.talaatharb.harness.web.WebSearchSettings;

/**
 * DuckDuckGo's key-less HTML endpoint. DuckDuckGo has no official web search API, so this is best-effort: when it
 * answers with a bot challenge or rate limit the provider fails with a clear message and does not retry or evade it.
 */
public class DuckDuckGoSearchProvider implements SearchProvider {
    public static final String ID = "duckduckgo";
    static final String ENDPOINT = "https://html.duckduckgo.com/html/";

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String displayName() {
        return "DuckDuckGo (HTML, no key)";
    }

    @Override
    public String description() {
        return "Free, no account. Unofficial endpoint: may be rate limited or challenged; use SearXNG for heavy use.";
    }

    @Override
    public List<SearchResult> search(String query, int limit, Map<String, String> config, WebSearchSettings settings)
            throws IOException {
        String html = SearchHttp.postForm(ENDPOINT, "q=" + SearchHttp.encode(query), settings);
        List<SearchResult> results = parse(html, limit);
        if (results.isEmpty() && html.toLowerCase(Locale.ROOT).contains("anomaly")) {
            throw new IOException("DuckDuckGo returned a bot challenge; try again later or choose another provider.");
        }
        return results;
    }

    static List<SearchResult> parse(String html, int limit) {
        Document doc = Jsoup.parse(html, ENDPOINT);
        List<SearchResult> out = new ArrayList<>();
        for (Element r : doc.select("div.result")) {
            if (r.hasClass("result--ad")) {
                continue;
            }
            Element link = r.selectFirst("a.result__a");
            if (link == null) {
                continue;
            }
            String url = unwrap(link.attr("href"));
            if (url.isBlank()) {
                continue;
            }
            Element snippet = r.selectFirst(".result__snippet");
            out.add(new SearchResult(link.text(), url, snippet == null ? "" : snippet.text(), ID));
            if (out.size() >= limit) {
                break;
            }
        }
        return out;
    }

    /** DuckDuckGo wraps targets as {@code //duckduckgo.com/l/?uddg=<encoded url>}. */
    static String unwrap(String href) {
        String h = href.strip();
        int i = h.indexOf("uddg=");
        if (i >= 0) {
            String v = h.substring(i + 5);
            int amp = v.indexOf('&');
            return URLDecoder.decode(amp < 0 ? v : v.substring(0, amp), StandardCharsets.UTF_8);
        }
        if (h.startsWith("//")) {
            return URI.create("https:" + h).toString();
        }
        return h;
    }
}
