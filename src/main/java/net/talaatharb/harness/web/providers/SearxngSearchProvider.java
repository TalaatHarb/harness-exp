package net.talaatharb.harness.web.providers;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import net.talaatharb.harness.web.SearchProvider;
import net.talaatharb.harness.web.SearchResult;
import net.talaatharb.harness.web.WebSearchSettings;

/** Self-hosted SearXNG metasearch (free). The instance must have the {@code json} output format enabled. */
public class SearxngSearchProvider implements SearchProvider {
    public static final String ID = "searxng";
    public static final String URL_KEY = "baseUrl";
    private static final ObjectMapper JSON = new ObjectMapper();

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String displayName() {
        return "SearXNG (self-hosted)";
    }

    @Override
    public String description() {
        return "Metasearch engine you host yourself; enable the json format in its settings.yml (search.formats).";
    }

    @Override
    public List<ConfigField> configFields() {
        return List.of(new ConfigField(URL_KEY, "SearXNG URL", "http://localhost:8080",
                "Base URL of your instance, e.g. http://localhost:8080"));
    }

    @Override
    public List<SearchResult> search(String query, int limit, Map<String, String> config, WebSearchSettings settings)
            throws IOException {
        String base = config.getOrDefault(URL_KEY, "").strip();
        if (base.isEmpty()) {
            base = configFields().get(0).defaultValue();
        }
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        String body = SearchHttp.get(base + "/search?format=json&q=" + SearchHttp.encode(query),
                Map.of("Accept", "application/json"), settings);
        return parse(body, limit);
    }

    static List<SearchResult> parse(String body, int limit) throws IOException {
        JsonNode root;
        try {
            root = JSON.readTree(body);
        } catch (IOException e) {
            throw new IOException("SearXNG did not return JSON; enable the json format on the instance.", e);
        }
        List<SearchResult> out = new ArrayList<>();
        for (JsonNode n : root.path("results")) {
            String url = n.path("url").asText("");
            if (url.isBlank()) {
                continue;
            }
            Map<String, String> meta = n.hasNonNull("engine") ? Map.of("engine", n.get("engine").asText()) : Map.of();
            out.add(new SearchResult(n.path("title").asText(""), url, n.path("content").asText(""),
                    n.path("publishedDate").asText(""), ID, meta));
            if (out.size() >= limit) {
                break;
            }
        }
        return out;
    }
}
