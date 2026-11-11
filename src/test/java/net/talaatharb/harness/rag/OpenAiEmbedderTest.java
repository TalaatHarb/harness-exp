package net.talaatharb.harness.rag;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;

import net.talaatharb.harness.config.HarnessSettings;

class OpenAiEmbedderTest {
    @Test
    void configuredCloudEmbeddingsAuthenticateAndRestoreInputOrder() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicReference<String> auth = new AtomicReference<>();
        AtomicReference<String> body = new AtomicReference<>();
        server.createContext("/v1/embeddings", ex -> {
            auth.set(ex.getRequestHeaders().getFirst("Authorization"));
            body.set(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] out = "{\"data\":[{\"index\":1,\"embedding\":[0.3,0.2]},{\"index\":0,\"embedding\":[0.1,0.4]}]}"
                    .getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, out.length);
            ex.getResponseBody().write(out);
            ex.close();
        });
        server.start();
        try {
            HarnessSettings settings = new HarnessSettings();
            settings.rag.embedder = RagSettings.EmbedderType.OPENAI;
            settings.rag.embeddingModel = "cloud-embed";
            settings.rag.embeddingBaseUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1/";
            settings.rag.embeddingApiKey = "independent-key";
            Embedder embedder = RagService.embedderFor(settings);
            List<float[]> vectors = embedder.embed(List.of("first", "second"));
            assertEquals("Bearer independent-key", auth.get());
            JsonNode sent = new ObjectMapper().readTree(body.get());
            assertEquals("cloud-embed", sent.path("model").asText());
            assertEquals("float", sent.path("encoding_format").asText());
            assertEquals("first", sent.path("input").get(0).asText());
            assertArrayEquals(new float[] { 0.1f, 0.4f }, vectors.get(0));
            assertArrayEquals(new float[] { 0.3f, 0.2f }, vectors.get(1));
            assertFalse(embedder.id().contains("independent-key"));
            settings.rag.embeddingBaseUrl += "other";
            assertNotEquals(embedder.id(), RagService.embedderFor(settings).id());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void rejectsMalformedVectorsAndReportsStatusWithoutLeakingResponseSecrets() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicReference<String> response = new AtomicReference<>();
        AtomicInteger status = new AtomicInteger(200);
        server.createContext("/embeddings", ex -> {
            byte[] out = response.get().getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(status.get(), out.length);
            ex.getResponseBody().write(out);
            ex.close();
        });
        server.start();
        try {
            Embedder embedder = new OpenAiEmbedder("http://127.0.0.1:" + server.getAddress().getPort(),
                    "test", "secret", Duration.ofSeconds(5));
            for (String invalid : List.of("not json", "{}", "{\"data\":[]}",
                    "{\"data\":[{\"index\":0,\"embedding\":[\"bad\"]}]}",
                    "{\"data\":[{\"index\":1,\"embedding\":[1]}]}",
                    "{\"data\":[{\"index\":0,\"embedding\":[]}]}")) {
                response.set(invalid);
                assertThrows(IOException.class, () -> embedder.embed(List.of("text")), invalid);
            }
            response.set("{\"data\":[{\"index\":0,\"embedding\":[1]},{\"index\":0,\"embedding\":[2]}]}");
            assertThrows(IOException.class, () -> embedder.embed(List.of("a", "b")));
            response.set("{\"error\":{\"message\":\"secret\"}}");
            status.set(401);
            IOException error = assertThrows(IOException.class, () -> embedder.embed(List.of("text")));
            assertTrue(error.getMessage().contains("401"));
            assertTrue(error.getMessage().contains("API key"));
            assertFalse(error.getMessage().contains("secret"));
        } finally {
            server.stop(0);
        }
    }
}
