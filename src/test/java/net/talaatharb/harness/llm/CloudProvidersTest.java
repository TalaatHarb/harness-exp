package net.talaatharb.harness.llm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpServer;

class CloudProvidersTest {
    private HttpServer server;
    private final Map<String, String> responses = new ConcurrentHashMap<>();
    private final Map<String, Integer> statuses = new ConcurrentHashMap<>();
    private final List<Headers> headers = new ArrayList<>();
    private final List<JsonNode> bodies = new ArrayList<>();

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            byte[] in = ex.getRequestBody().readAllBytes();
            synchronized (this) {
                headers.add(ex.getRequestHeaders());
                bodies.add(in.length == 0 ? null : HttpJson.MAPPER.readTree(in));
            }
            String path = ex.getRequestURI().getPath();
            byte[] out = responses.getOrDefault(path, "{}").getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(statuses.getOrDefault(path, 200), out.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(out);
            }
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private ModelSettings config(String provider, String prefix) {
        ModelSettings s = new ModelSettings();
        s.provider = provider;
        s.baseUrl = "http://127.0.0.1:" + server.getAddress().getPort() + prefix + "/";
        s.apiKey = "test-key";
        s.model = "m";
        s.requestTimeoutSeconds = 10;
        return s;
    }

    private static List<ChatMessage> conversation() {
        Attachment png = new Attachment("p.png", "image/png", Attachment.Kind.IMAGE, 2, null,
                Base64.getEncoder().encodeToString(new byte[] { 1, 2 }), false);
        return List.of(ChatMessage.system("be brief"), ChatMessage.user("look", List.of(png)),
                ChatMessage.assistant("calling a tool"), ChatMessage.tool("current_time", "noon"),
                ChatMessage.user("so?"));
    }

    @Test
    void openAiListsModelsAndChatsWithBearerKey() throws Exception {
        responses.put("/v1/models", "{\"data\":[{\"id\":\"gpt-b\"},{\"id\":\"gpt-a\"}]}");
        responses.put("/v1/chat/completions", "{\"choices\":[{\"message\":{\"content\":\"hi there\"}}]}");
        OpenAiModelProvider p = new OpenAiModelProvider();
        ModelSettings c = config("openai", "/v1");

        assertEquals(List.of("gpt-a", "gpt-b"), p.listModels(c));
        ModelReply reply = p.chat(c, new ModelRequest("m", conversation(), false), null);

        assertEquals("hi there", reply.content());
        assertEquals("Bearer test-key", headers.get(1).getFirst("Authorization"));
        JsonNode messages = bodies.get(1).path("messages");
        assertEquals("system", messages.get(0).path("role").asText());
        assertEquals("image_url", messages.get(1).path("content").get(1).path("type").asText());
        assertTrue(messages.get(1).path("content").get(1).path("image_url").path("url").asText()
                .startsWith("data:image/png;base64,"));
        assertEquals("user", messages.get(3).path("role").asText());
        assertTrue(messages.get(3).path("content").asText().contains("current_time"));
    }

    @Test
    void openAiStreamsTokens() throws Exception {
        responses.put("/v1/chat/completions", """
                data: {"choices":[{"delta":{"content":"Hel"}}]}

                data: {"choices":[{"delta":{"content":"lo"}}]}

                data: [DONE]
                """);
        List<String> tokens = new ArrayList<>();
        ModelReply reply = new OpenAiModelProvider().chat(config("openai", "/v1"),
                new ModelRequest("m", List.of(ChatMessage.user("hi")), true), tokens::add);

        assertEquals("Hello", reply.content());
        assertEquals(List.of("Hel", "lo"), tokens);
        assertTrue(bodies.get(0).path("stream").asBoolean());
    }

    @Test
    void httpErrorsAreReadable() {
        responses.put("/v1/models", "{\"error\":{\"message\":\"Incorrect API key provided\"}}");
        statuses.put("/v1/models", 401);
        ModelProviderException e = assertThrows(ModelProviderException.class,
                () -> new OpenAiModelProvider().listModels(config("openai", "/v1")));
        assertTrue(e.getMessage().contains("401"));
        assertTrue(e.getMessage().contains("Incorrect API key provided"));
        assertFalse(new OpenAiModelProvider().ping(config("openai", "/v1")));
    }

    @Test
    void anthropicSendsSystemSeparatelyAndAlternatesRoles() throws Exception {
        responses.put("/v1/messages", "{\"content\":[{\"type\":\"thinking\",\"thinking\":\"hmm\"},"
                + "{\"type\":\"text\",\"text\":\"Claude here\"}]}");
        ModelSettings c = config("anthropic", "");
        c.maxOutputTokens = 123;

        ModelReply reply = new AnthropicModelProvider().chat(c, new ModelRequest("m", conversation(), false), null);

        assertEquals("Claude here", reply.content());
        assertEquals("hmm", reply.thinking());
        assertEquals("test-key", headers.get(0).getFirst("x-api-key"));
        assertEquals(AnthropicModelProvider.API_VERSION, headers.get(0).getFirst("anthropic-version"));
        JsonNode body = bodies.get(0);
        assertEquals("be brief", body.path("system").asText());
        assertEquals(123, body.path("max_tokens").asInt());
        JsonNode messages = body.path("messages");
        // user(look + image), assistant, then the tool result and "so?" merged into one user turn
        assertEquals(3, messages.size());
        assertEquals("image", messages.get(0).path("content").get(0).path("type").asText());
        assertEquals("assistant", messages.get(1).path("role").asText());
        assertEquals("user", messages.get(2).path("role").asText());
        assertEquals(2, messages.get(2).path("content").size());
    }

    @Test
    void anthropicStreamsTextDeltas() throws Exception {
        responses.put("/v1/messages", """
                event: message_start
                data: {"type":"message_start","message":{}}

                event: content_block_delta
                data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"Hi"}}

                event: content_block_delta
                data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"!"}}

                event: message_stop
                data: {"type":"message_stop"}
                """);
        List<String> tokens = new ArrayList<>();
        ModelReply reply = new AnthropicModelProvider().chat(config("anthropic", ""),
                new ModelRequest("m", List.of(ChatMessage.user("hi")), true), tokens::add);
        assertEquals("Hi!", reply.content());
        assertEquals(List.of("Hi", "!"), tokens);
    }

    @ParameterizedTest
    @ValueSource(booleans = { false, true })
    void samplingOptionsReachOpenAiAndOllamaWithDefaultsOmitted(boolean stream) throws Exception {
        responses.put("/v1/chat/completions", stream
                ? "data: {\"choices\":[{\"delta\":{\"content\":\"ok\"}}]}\n\ndata: [DONE]\n\n"
                : "{\"choices\":[{\"message\":{\"content\":\"ok\"}}]}");
        responses.put("/api/chat",
                "{\"model\":\"m\",\"created_at\":\"2026-01-01T00:00:00Z\","
                + "\"message\":{\"role\":\"assistant\",\"content\":\"ok\"},\"done\":true}\n");
        for (ModelProvider provider : List.of(new OpenAiModelProvider(), new OllamaModelProvider())) {
            ModelSettings c = config(provider.id(), provider.id().equals("openai") ? "/v1" : "");
            ModelRequest request = new ModelRequest("m", List.of(ChatMessage.user("hi")), stream);
            assertEquals("ok", provider.chat(c, request, null).content());
            JsonNode defaults = bodies.get(bodies.size() - 1);
            JsonNode defaultOptions = provider.id().equals("ollama") ? defaults.path("options") : defaults;
            assertFalse(defaultOptions.has("temperature"), defaults.toString());
            assertFalse(defaultOptions.has("seed"), defaults.toString());

            c.temperature = 0.25;
            c.seed = 123456L;
            assertEquals("ok", provider.chat(c, request, null).content());
            JsonNode sent = bodies.get(bodies.size() - 1);
            JsonNode options = provider.id().equals("ollama") ? sent.path("options") : sent;
            assertEquals(0.25, options.path("temperature").asDouble(), sent.toString());
            assertEquals(123456, options.path("seed").asInt(), sent.toString());
            if (provider.id().equals("ollama")) {
                assertEquals(2, options.size(), "No other library defaults should be injected");
            }

            c.temperature = null;
            c.seed = 0L;
            provider.chat(c, request, null);
            JsonNode seedOnly = bodies.get(bodies.size() - 1);
            seedOnly = provider.id().equals("ollama") ? seedOnly.path("options") : seedOnly;
            assertFalse(seedOnly.has("temperature"));
            assertEquals(0, seedOnly.path("seed").asInt(-1));
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = { false, true })
    void anthropicTemperatureIsOptionalAndSeedIsRejectedBeforeNetwork(boolean stream) throws Exception {
        responses.put("/v1/messages", stream
                ? "data: {\"type\":\"content_block_delta\",\"delta\":{\"type\":\"text_delta\",\"text\":\"ok\"}}\n\n"
                : "{\"content\":[{\"type\":\"text\",\"text\":\"ok\"}]}");
        ModelSettings c = config("anthropic", "");
        ModelRequest request = new ModelRequest("m", List.of(ChatMessage.user("hi")), stream);
        AnthropicModelProvider provider = new AnthropicModelProvider();
        assertEquals("ok", provider.chat(c, request, null).content());
        assertFalse(bodies.get(0).has("temperature"));
        assertFalse(bodies.get(0).has("seed"));
        c.temperature = 0.5;
        assertEquals("ok", provider.chat(c, request, null).content());
        assertEquals(0.5, bodies.get(1).path("temperature").asDouble());
        assertFalse(bodies.get(1).has("seed"));
        c.seed = 42L;
        assertTrue(assertThrows(ModelProviderException.class, () -> provider.chat(c, request, null))
                .getMessage().contains("does not support seed"));
        assertEquals(2, bodies.size());
    }

    @Test
    void openAiLargeSeedsAreNotTruncatedAndUnsupportedOptionsFailExplicitly() throws Exception {
        responses.put("/v1/chat/completions", "{\"choices\":[{\"message\":{\"content\":\"ok\"}}]}");
        ModelSettings c = config("openai", "/v1");
        c.seed = Long.MAX_VALUE;
        c.temperature = 0.0;
        ModelRequest request = new ModelRequest("m", List.of(ChatMessage.user("hi")), false);
        new OpenAiModelProvider().chat(c, request, null);
        assertEquals(Long.MAX_VALUE, bodies.get(0).path("seed").asLong());
        assertEquals(0.0, bodies.get(0).path("temperature").asDouble());
        statuses.put("/v1/chat/completions", 400);
        responses.put("/v1/chat/completions", "{\"error\":{\"message\":\"Unsupported parameter: temperature\"}}");
        ModelProviderException error = assertThrows(ModelProviderException.class,
                () -> new OpenAiModelProvider().chat(c, request, null));
        assertTrue(error.getMessage().contains("Unsupported parameter: temperature"));
        assertEquals(2, bodies.size(), "Do not silently retry with requested options removed");
    }

    @Test
    void anthropicListsModels() throws Exception {
        responses.put("/v1/models", "{\"data\":[{\"id\":\"claude-x\"}]}");
        assertEquals(List.of("claude-x"), new AnthropicModelProvider().listModels(config("anthropic", "")));
    }
}
