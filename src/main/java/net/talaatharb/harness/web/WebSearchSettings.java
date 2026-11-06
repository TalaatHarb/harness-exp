package net.talaatharb.harness.web;

import java.util.HashMap;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** Shared configuration of the web search tools, stored as {@code tools.web}. */
@JsonIgnoreProperties(ignoreUnknown = true)
public class WebSearchSettings {
    public static final String DEFAULT_PROVIDER = "duckduckgo";

    /** {@link SearchProvider#id()} used by both web tools. */
    public String provider = DEFAULT_PROVIDER;
    /** Provider-specific values, keyed by provider id and then by field key. */
    public Map<String, Map<String, String>> providerConfig = new HashMap<>();
    /** Search results requested from the provider. */
    public int maxResults = 8;
    /** Pages fetched and extracted per tool call. */
    public int maxPages = 3;
    public int searchTimeoutSeconds = 15;
    public int fetchTimeoutSeconds = 15;
    /** Pages larger than this (bytes) are cut off while downloading. */
    public int maxResponseBytes = 2_000_000;
    /** Extracted text per page is cut to this many characters. */
    public int maxCharsPerPage = 2_500;
    /** Extracted text per tool call is cut to this many characters across all pages. */
    public int maxTotalChars = 6_000;
    /** Pages indexed into RAG are cut to this many characters (chunked by RAG afterwards). */
    public int maxIndexCharsPerPage = 60_000;
    /** Text shorter than this is treated as "no readable content". */
    public int minContentChars = 200;
    public boolean respectRobotsTxt = true;
    /** Minimum pause between two requests to the same host. */
    public int minHostDelayMillis = 1_000;
    /** Fetching hosts that resolve to loopback/private addresses is refused unless enabled. */
    public boolean allowPrivateHosts = false;
    public String userAgent = "HarnessBot/0.1 (+https://github.com/TalaatHarb/harness-exp)";

    public Map<String, String> configFor(String providerId) {
        Map<String, String> m = providerConfig == null ? null : providerConfig.get(providerId);
        return m == null ? Map.of() : m;
    }

    /** Clamps values that were hand-edited into something unusable. */
    public WebSearchSettings sanitized() {
        maxResults = Math.max(1, Math.min(maxResults, 30));
        maxPages = Math.max(1, Math.min(maxPages, 10));
        searchTimeoutSeconds = Math.max(1, searchTimeoutSeconds);
        fetchTimeoutSeconds = Math.max(1, fetchTimeoutSeconds);
        maxResponseBytes = Math.max(10_000, maxResponseBytes);
        maxCharsPerPage = Math.max(200, maxCharsPerPage);
        maxTotalChars = Math.max(maxCharsPerPage, maxTotalChars);
        maxIndexCharsPerPage = Math.max(maxCharsPerPage, maxIndexCharsPerPage);
        minContentChars = Math.max(0, minContentChars);
        minHostDelayMillis = Math.max(0, minHostDelayMillis);
        if (provider == null || provider.isBlank()) {
            provider = DEFAULT_PROVIDER;
        }
        if (providerConfig == null) {
            providerConfig = new HashMap<>();
        }
        if (userAgent == null || userAgent.isBlank()) {
            userAgent = new WebSearchSettings().userAgent;
        }
        return this;
    }
}
