package net.talaatharb.harness.rag;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import net.talaatharb.harness.llm.OpenAiModelProvider;

/** Batched embeddings from OpenAI or a compatible service. Redirects never forward credentials. */
public class OpenAiEmbedder implements Embedder {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HttpClient CLIENT = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15))
            .followRedirects(HttpClient.Redirect.NEVER).build();
    private final String baseUrl;
    private final String model;
    private final String apiKey;
    private final Duration timeout;

    public OpenAiEmbedder(String baseUrl, String model, String apiKey, Duration timeout) {
        String base = baseUrl == null || baseUrl.isBlank() ? OpenAiModelProvider.DEFAULT_URL : baseUrl.strip();
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        URI uri = URI.create(base);
        if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                || uri.getHost() == null || uri.getRawUserInfo() != null || uri.getRawQuery() != null
                || uri.getRawFragment() != null) {
            throw new IllegalArgumentException("Embedding base URL must be an http(s) URL without credentials, query or fragment");
        }
        this.baseUrl = base;
        this.model = model == null || model.isBlank() ? RagSettings.DEFAULT_OPENAI_MODEL : model.strip();
        this.apiKey = apiKey == null ? "" : apiKey.strip();
        this.timeout = timeout;
    }

    @Override
    public String id() {
        return "openai:" + baseUrl + ":" + model;
    }

    @Override
    public List<float[]> embed(List<String> texts) throws IOException {
        List<float[]> vectors = new ArrayList<>(texts.size());
        for (int start = 0; start < texts.size(); start += 16) {
            vectors.addAll(batch(texts.subList(start, Math.min(texts.size(), start + 16))));
        }
        return vectors;
    }

    private List<float[]> batch(List<String> texts) throws IOException {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(baseUrl + "/embeddings")).timeout(timeout)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(
                        Map.of("model", model, "input", texts, "encoding_format", "float"))));
        if (!apiKey.isEmpty()) {
            request.header("Authorization", "Bearer " + apiKey);
        }
        HttpResponse<String> response;
        try {
            response = CLIENT.send(request.build(), HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Embedding cancelled", e);
        }
        if (response.statusCode() / 100 != 2) {
            String hint = switch (response.statusCode()) {
                case 401, 403 -> " Check the embedding API key and permissions.";
                case 404 -> " Check the base URL and embedding model.";
                case 429 -> " Rate limit or quota exceeded; retry later.";
                default -> "";
            };
            throw new IOException("Cloud embeddings failed (HTTP " + response.statusCode() + ")." + hint);
        }
        JsonNode node;
        try {
            node = JSON.readTree(response.body());
        } catch (IOException e) {
            throw new IOException("Embedding service returned invalid JSON");
        }
        JsonNode data = node == null ? null : node.get("data");
        if (data == null || !data.isArray() || data.size() != texts.size()) {
            throw new IOException("Embedding service returned an unexpected number of vectors");
        }
        List<float[]> vectors = new ArrayList<>(java.util.Collections.nCopies(texts.size(), null));
        int dimensions = -1;
        for (JsonNode item : data) {
            JsonNode indexNode = item.get("index");
            int index = indexNode != null && indexNode.isIntegralNumber() && indexNode.canConvertToInt()
                    ? indexNode.intValue() : -1;
            JsonNode values = item.path("embedding");
            if (index < 0 || index >= texts.size() || vectors.get(index) != null || !values.isArray()
                    || values.isEmpty() || (dimensions != -1 && dimensions != values.size())) {
                throw new IOException("Embedding service returned malformed vectors or indices");
            }
            dimensions = values.size();
            float[] vector = new float[dimensions];
            for (int i = 0; i < dimensions; i++) {
                if (!values.get(i).isNumber() || !Float.isFinite(values.get(i).floatValue())) {
                    throw new IOException("Embedding service returned a non-finite or non-numeric vector");
                }
                vector[i] = values.get(i).floatValue();
            }
            vectors.set(index, vector);
        }
        return vectors;
    }
}
