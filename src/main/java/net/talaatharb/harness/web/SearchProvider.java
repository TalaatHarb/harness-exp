package net.talaatharb.harness.web;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * A web search backend. Providers are bound with a Guice {@code Multibinder} and listed in the Tools settings, so a new
 * one needs no change to the tools. Only providers that are free (or have a usable free tier) belong here.
 */
public interface SearchProvider {

    /** One provider-specific setting shown in the Tools section, e.g. a SearXNG base URL. */
    record ConfigField(String key, String label, String defaultValue, String help) {
    }

    /** Stable id stored in the settings. */
    String id();

    String displayName();

    default String description() {
        return "";
    }

    default List<ConfigField> configFields() {
        return List.of();
    }

    /**
     * @param config values for {@link #configFields()} (missing keys mean the default)
     * @throws IOException on network errors, a blocked/rate-limited response or a missing configuration
     */
    List<SearchResult> search(String query, int limit, Map<String, String> config, WebSearchSettings settings)
            throws IOException;
}
