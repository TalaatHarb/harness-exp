package net.talaatharb.harness.web.providers;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.jsoup.Jsoup;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import net.talaatharb.harness.web.SearchProvider;
import net.talaatharb.harness.web.SearchResult;
import net.talaatharb.harness.web.WebSearchSettings;

/** Wikipedia's official, free MediaWiki search API: reliable, but limited to encyclopedic topics. */
public class WikipediaSearchProvider implements SearchProvider {
    public static final String ID = "wikipedia";
    public static final String LANG_KEY = "language";
    private static final ObjectMapper JSON = new ObjectMapper();

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String displayName() {
        return "Wikipedia (official API)";
    }

    @Override
    public String description() {
        return "Free official API, no account. Only searches Wikipedia articles.";
    }

    @Override
    public List<ConfigField> configFields() {
        return List.of(new ConfigField(LANG_KEY, "Wikipedia language", "en", "Language code, e.g. en, de, fr"));
    }

    @Override
    public List<SearchResult> search(String query, int limit, Map<String, String> config, WebSearchSettings settings)
            throws IOException {
        String lang = config.getOrDefault(LANG_KEY, "en").strip();
        if (!lang.matches("[a-z]{2,12}(-[a-z]{2,12})?")) {
            lang = "en";
        }
        String body = SearchHttp.get("https://" + lang + ".wikipedia.org/w/api.php?action=query&list=search&format=json"
                + "&srlimit=" + limit + "&srsearch=" + SearchHttp.encode(query),
                Map.of("Accept", "application/json"), settings);
        return parse(body, lang, limit);
    }

    static List<SearchResult> parse(String body, String lang, int limit) throws IOException {
        List<SearchResult> out = new ArrayList<>();
        JsonNode response = JSON.readTree(body);
        if (response == null || !response.path("error").isMissingNode()) {
            throw new IOException("Wikipedia search failed: " + (response == null ? "empty response"
                    : response.path("error").path("info").asText("API error")));
        }
        if (!response.path("query").path("search").isArray()) {
            throw new IOException("Wikipedia search returned no search result array");
        }
        for (JsonNode n : response.path("query").path("search")) {
            String title = n.path("title").asText("");
            if (title.isBlank()) {
                continue;
            }
            String url = "https://" + lang + ".wikipedia.org/wiki/" + SearchHttp.encode(title.replace(' ', '_'));
            out.add(new SearchResult(title, url, Jsoup.parse(n.path("snippet").asText("")).text(),
                    "", ID, Map.of("wordcount", n.path("wordcount").asText("0"),
                            "modifiedDate", n.path("timestamp").asText(""))));
            if (out.size() >= limit) {
                break;
            }
        }
        return out;
    }
}
