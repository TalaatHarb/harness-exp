package net.talaatharb.harness.web.providers;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

import net.talaatharb.harness.web.WebSearchSettings;

/** Small HTTP helper shared by the search providers. Responses are returned as text; non-2xx becomes an IOException. */
public class SearchHttp {
    private static final HttpClient CLIENT = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(10)).build();

    private SearchHttp() {
    }

    public static String encode(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    public static String get(String url, Map<String, String> headers, WebSearchSettings settings) throws IOException {
        return send(builder(url).GET(), headers, settings);
    }

    public static String postForm(String url, String form, WebSearchSettings settings) throws IOException {
        return send(builder(url).header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form)), Map.of(), settings);
    }

    private static HttpRequest.Builder builder(String url) throws IOException {
        try {
            return HttpRequest.newBuilder(URI.create(url));
        } catch (IllegalArgumentException e) {
            throw new IOException("Invalid search URL: " + e.getMessage(), e);
        }
    }

    private static String send(HttpRequest.Builder builder, Map<String, String> headers, WebSearchSettings settings)
            throws IOException {
        builder.timeout(Duration.ofSeconds(settings.searchTimeoutSeconds)).header("User-Agent", settings.userAgent);
        headers.forEach(builder::header);
        try {
            HttpResponse<String> r = CLIENT.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            int status = r.statusCode();
            if (status == 202 || status == 403 || status == 429) {
                throw new IOException("The search service refused the request (HTTP " + status
                        + "; rate limit or bot protection). Try again later or choose another provider.");
            }
            if (status < 200 || status >= 300) {
                throw new IOException("Search request failed: HTTP " + status);
            }
            return r.body();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Search interrupted", e);
        }
    }
}
