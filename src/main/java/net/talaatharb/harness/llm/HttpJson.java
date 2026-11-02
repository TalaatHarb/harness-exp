package net.talaatharb.harness.llm;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Stream;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/** Small JSON-over-HTTP helper shared by the cloud providers (plain JDK client, no extra dependencies). */
final class HttpJson {
    static final ObjectMapper MAPPER = new ObjectMapper();
    private static final HttpClient CLIENT = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15))
            .followRedirects(HttpClient.Redirect.NORMAL).build();

    private HttpJson() {
    }

    /** The configured key, or the given environment variable when the field is blank. */
    static String apiKey(ModelSettings config, String envVar) {
        if (config.apiKey != null && !config.apiKey.isBlank()) {
            return config.apiKey.trim();
        }
        String env = System.getenv(envVar);
        return env == null ? "" : env.trim();
    }

    static String url(ModelSettings config, String defaultBase, String path) {
        String base = config.baseUrl == null || config.baseUrl.isBlank() ? defaultBase : config.baseUrl.trim();
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return base + path;
    }

    private static HttpRequest.Builder request(String url, Map<String, String> headers, ModelSettings config) {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(Math.max(5, config.requestTimeoutSeconds)));
        headers.forEach(b::header);
        return b;
    }

    static JsonNode get(String url, Map<String, String> headers, ModelSettings config, String provider)
            throws ModelProviderException {
        return send(request(url, headers, config).GET().build(), provider);
    }

    static JsonNode post(String url, Map<String, String> headers, Object body, ModelSettings config,
            String provider) throws ModelProviderException {
        return send(postRequest(url, headers, body, config), provider);
    }

    /** POSTs and hands every server-sent-event {@code data:} payload to {@code onData}. */
    static void postStream(String url, Map<String, String> headers, Object body, ModelSettings config,
            String provider, Consumer<JsonNode> onData) throws ModelProviderException {
        HttpRequest req = postRequest(url, headers, body, config);
        HttpResponse<Stream<String>> res = call(req, HttpResponse.BodyHandlers.ofLines(), provider);
        try (Stream<String> lines = res.body()) {
            if (res.statusCode() / 100 != 2) {
                throw new ModelProviderException(error(provider, res.statusCode(),
                        String.join("\n", lines.toList())));
            }
            for (String line : (Iterable<String>) lines::iterator) {
                if (Thread.currentThread().isInterrupted()) {
                    throw new ModelProviderException(provider + " request cancelled");
                }
                if (!line.startsWith("data:")) {
                    continue;
                }
                String data = line.substring(5).trim();
                if (data.isEmpty() || data.equals("[DONE]")) {
                    continue;
                }
                onData.accept(MAPPER.readTree(data));
            }
        } catch (IOException | RuntimeException e) {
            throw new ModelProviderException(provider + " stream failed: " + e.getMessage(), e);
        }
    }

    private static HttpRequest postRequest(String url, Map<String, String> headers, Object body,
            ModelSettings config) throws ModelProviderException {
        try {
            return request(url, headers, config).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(body))).build();
        } catch (IOException e) {
            throw new ModelProviderException("Could not encode request: " + e.getMessage(), e);
        }
    }

    private static JsonNode send(HttpRequest req, String provider) throws ModelProviderException {
        HttpResponse<String> res = call(req, HttpResponse.BodyHandlers.ofString(), provider);
        if (res.statusCode() / 100 != 2) {
            throw new ModelProviderException(error(provider, res.statusCode(), res.body()));
        }
        try {
            return MAPPER.readTree(res.body());
        } catch (IOException e) {
            throw new ModelProviderException(provider + " returned invalid JSON: " + e.getMessage(), e);
        }
    }

    private static <T> HttpResponse<T> call(HttpRequest req, HttpResponse.BodyHandler<T> handler, String provider)
            throws ModelProviderException {
        try {
            return CLIENT.send(req, handler);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ModelProviderException(provider + " request cancelled", e);
        } catch (IOException e) {
            throw new ModelProviderException("Could not reach " + provider + " at " + req.uri() + ": "
                    + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()), e);
        }
    }

    /** Pulls {@code error.message} out of OpenAI/Anthropic-style error bodies. */
    static String error(String provider, int status, String body) {
        String detail = body == null ? "" : body.strip();
        try {
            JsonNode node = MAPPER.readTree(detail);
            JsonNode message = node.path("error").path("message");
            if (message.isTextual()) {
                detail = message.asText();
            }
        } catch (IOException | RuntimeException ignored) {
            // not JSON: keep the raw body
        }
        if (detail.length() > 500) {
            detail = detail.substring(0, 500) + "…";
        }
        String hint = status == 401 || status == 403 ? " (check the API key)" : "";
        return provider + " returned HTTP " + status + hint + (detail.isEmpty() ? "" : ": " + detail);
    }
}
