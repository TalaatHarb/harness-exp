package net.talaatharb.harness.web;

import java.util.Map;

/** One normalized hit from any {@link SearchProvider}. {@code publishedDate} and {@code metadata} may be empty. */
public record SearchResult(String title, String url, String snippet, String publishedDate, String provider,
        Map<String, String> metadata) {

    public SearchResult {
        title = title == null ? "" : title.strip();
        url = url == null ? "" : url.strip();
        snippet = snippet == null ? "" : snippet.strip();
        publishedDate = publishedDate == null ? "" : publishedDate.strip();
        provider = provider == null ? "" : provider;
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }

    public SearchResult(String title, String url, String snippet, String provider) {
        this(title, url, snippet, "", provider, Map.of());
    }
}
