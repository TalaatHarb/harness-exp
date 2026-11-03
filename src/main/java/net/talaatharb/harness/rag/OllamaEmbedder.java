package net.talaatharb.harness.rag;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/** Embeddings from Ollama's {@code POST /api/embed} (local daemon or Ollama cloud). */
public class OllamaEmbedder implements Embedder {
    static final int BATCH = 16;
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HttpClient CLIENT = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15))
            .followRedirects(HttpClient.Redirect.NORMAL).build();

    private final String baseUrl;
    private final String model;
    private final String apiKey;
    private final Duration timeout;

    public OllamaEmbedder(String baseUrl, String model, String apiKey, Duration timeout) {
        String base = baseUrl == null ? "" : baseUrl.strip();
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        this.baseUrl = base;
        this.model = model == null || model.isBlank() ? RagSettings.DEFAULT_OLLAMA_MODEL : model.strip();
        this.apiKey = apiKey == null ? "" : apiKey.strip();
        this.timeout = timeout;
    }

    @Override
    public String id() {
        return "ollama:" + model;
    }

    public String baseUrl() {
        return baseUrl;
    }

    @Override
    public List<float[]> embed(List<String> texts) throws IOException {
        List<float[]> vectors = new ArrayList<>(texts.size());
        for (int start = 0; start < texts.size(); start += BATCH) {
            vectors.addAll(batch(texts.subList(start, Math.min(texts.size(), start + BATCH))));
        }
        return vectors;
    }

    private List<float[]> batch(List<String> texts) throws IOException {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("input", texts);
        body.put("truncate", true);
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(baseUrl + "/api/embed")).timeout(timeout)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)));
        if (!apiKey.isEmpty()) {
            request.header("Authorization", "Bearer " + apiKey);
        }
        HttpResponse<String> response;
        try {
            response = CLIENT.send(request.build(), HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Embedding cancelled", e);
        } catch (IOException e) {
            throw new IOException("Could not reach Ollama at " + baseUrl + " for embeddings: "
                    + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()), e);
        }
        JsonNode node;
        try {
            node = JSON.readTree(response.body());
        } catch (IOException e) {
            node = null;
        }
        if (response.statusCode() / 100 != 2 || node == null || !node.path("embeddings").isArray()) {
            throw new IOException(error(response.statusCode(), node, response.body()));
        }
        JsonNode embeddings = node.path("embeddings");
        if (embeddings.size() != texts.size()) {
            throw new IOException("Ollama returned " + embeddings.size() + " embeddings for " + texts.size()
                    + " texts");
        }
        List<float[]> vectors = new ArrayList<>(texts.size());
        for (JsonNode e : embeddings) {
            float[] v = new float[e.size()];
            for (int i = 0; i < v.length; i++) {
                v[i] = (float) e.get(i).asDouble();
            }
            vectors.add(v);
        }
        return vectors;
    }

    private String error(int status, JsonNode node, String body) {
        String detail = body == null ? "" : body.strip();
        if (node != null) {
            JsonNode error = node.path("error");
            detail = error.isTextual() ? error.asText() : error.path("message").asText(detail);
        }
        if (detail.length() > 300) {
            detail = detail.substring(0, 300) + "…";
        }
        String lower = detail.toLowerCase(Locale.ROOT);
        String hint = "";
        if (lower.contains("does not support embeddings") || lower.contains("--embeddings")) {
            hint = " '" + model + "' is not an embedding model; use one such as " + RagSettings.DEFAULT_OLLAMA_MODEL
                    + " (Harness → RAG).";
        } else if (status == 404 || lower.contains("not found") || lower.contains("pull")) {
            hint = " Pull the model with `ollama pull " + model + "`, or pick another embedder in Harness → RAG.";
        } else if (status == 401 || status == 403) {
            hint = " Check the Ollama API key.";
        }
        return "Ollama embeddings with '" + model + "' failed (HTTP " + status + ")"
                + (detail.isEmpty() ? "." : ": " + detail + ".") + hint;
    }
}
