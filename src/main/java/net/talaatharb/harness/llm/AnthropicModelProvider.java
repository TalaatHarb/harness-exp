package net.talaatharb.harness.llm;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.inject.Singleton;

/** Anthropic Messages API provider (Claude). The key comes from the settings or {@code ANTHROPIC_API_KEY}. */
@Singleton
public class AnthropicModelProvider implements ModelProvider {
    public static final String ID = "anthropic";
    public static final String DEFAULT_URL = "https://api.anthropic.com";
    public static final String API_KEY_ENV = "ANTHROPIC_API_KEY";
    public static final String API_VERSION = "2023-06-01";
    /** Image types the Messages API accepts; others are described in text only. */
    static final Set<String> IMAGE_TYPES = Set.of("image/jpeg", "image/png", "image/gif", "image/webp");
    private static final Logger LOG = LoggerFactory.getLogger(AnthropicModelProvider.class);

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String displayName() {
        return "Anthropic (Claude)";
    }

    @Override
    public String defaultBaseUrl() {
        return DEFAULT_URL;
    }

    @Override
    public String apiKeyEnvVar() {
        return API_KEY_ENV;
    }

    private static Map<String, String> headers(ModelSettings config) {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("anthropic-version", API_VERSION);
        String key = HttpJson.apiKey(config, API_KEY_ENV);
        if (!key.isEmpty()) {
            headers.put("x-api-key", key);
        }
        return headers;
    }

    @Override
    public boolean ping(ModelSettings config) {
        try {
            listModels(config);
            return true;
        } catch (ModelProviderException e) {
            LOG.warn("Anthropic ping failed: {}", e.getMessage());
            return false;
        }
    }

    @Override
    public List<String> listModels(ModelSettings config) throws ModelProviderException {
        JsonNode res = HttpJson.get(HttpJson.url(config, DEFAULT_URL, "/v1/models?limit=1000"), headers(config),
                config, "Anthropic");
        List<String> models = new ArrayList<>();
        res.path("data").forEach(m -> models.add(m.path("id").asText()));
        return models.stream().filter(s -> !s.isBlank()).toList();
    }

    @Override
    public ModelReply chat(ModelSettings config, ModelRequest request, Consumer<String> tokenSink)
            throws ModelProviderException {
        long start = System.currentTimeMillis();
        config.validateSampling(ID);
        ObjectNode body = body(request, config.maxOutputTokens);
        if (config.temperature != null) {
            body.put("temperature", config.temperature);
        }
        String url = HttpJson.url(config, DEFAULT_URL, "/v1/messages");
        if (!request.stream()) {
            JsonNode res = HttpJson.post(url, headers(config), body, config, "Anthropic");
            StringBuilder content = new StringBuilder();
            StringBuilder thinking = new StringBuilder();
            for (JsonNode block : res.path("content")) {
                switch (block.path("type").asText()) {
                    case "text" -> content.append(block.path("text").asText());
                    case "thinking" -> thinking.append(block.path("thinking").asText());
                    default -> { }
                }
            }
            return new ModelReply(content.toString(), thinking.isEmpty() ? null : thinking.toString(),
                    System.currentTimeMillis() - start, ModelReply.count(res.path("usage").get("input_tokens")),
                    ModelReply.count(res.path("usage").get("output_tokens")));
        }
        body.put("stream", true);
        StringBuilder content = new StringBuilder();
        StringBuilder thinking = new StringBuilder();
        String[] error = { null };
        Integer[] usage = { null, null };
        HttpJson.postStream(url, headers(config), body, config, "Anthropic", event -> {
            switch (event.path("type").asText()) {
                case "message_start" -> usage[0] = ModelReply.count(
                        event.path("message").path("usage").get("input_tokens"));
                case "message_delta" -> {
                    Integer output = ModelReply.count(event.path("usage").get("output_tokens"));
                    if (output != null) {
                        usage[1] = output;
                    }
                }
                case "content_block_delta" -> {
                    JsonNode delta = event.path("delta");
                    if ("text_delta".equals(delta.path("type").asText())) {
                        String text = delta.path("text").asText("");
                        content.append(text);
                        if (tokenSink != null && !text.isEmpty()) {
                            tokenSink.accept(text);
                        }
                    } else if ("thinking_delta".equals(delta.path("type").asText())) {
                        thinking.append(delta.path("thinking").asText(""));
                    }
                }
                case "error" -> error[0] = event.path("error").path("message").asText("stream error");
                default -> { }
            }
        });
        if (error[0] != null) {
            throw new ModelProviderException("Anthropic stream failed: " + error[0]);
        }
        return new ModelReply(content.toString(), thinking.isEmpty() ? null : thinking.toString(),
                System.currentTimeMillis() - start, usage[0], usage[1]);
    }

    /**
     * Builds the request: system messages go to the top-level {@code system} field, tool results become user turns,
     * and consecutive same-role messages are merged because the API requires alternating user/assistant turns that
     * start with the user.
     */
    static ObjectNode body(ModelRequest request, int maxTokens) {
        ObjectNode body = HttpJson.MAPPER.createObjectNode();
        body.put("model", request.model());
        body.put("max_tokens", maxTokens > 0 ? maxTokens : ModelSettings.DEFAULT_MAX_OUTPUT_TOKENS);
        StringBuilder system = new StringBuilder();
        ArrayNode messages = body.putArray("messages");
        String lastRole = null;
        ArrayNode blocks = null;
        for (ChatMessage m : request.messages()) {
            if (m.role() == Role.SYSTEM) {
                system.append(system.isEmpty() ? "" : "\n\n").append(m.content());
                continue;
            }
            String role = m.role() == Role.ASSISTANT ? "assistant" : "user";
            if (lastRole == null && role.equals("assistant")) {
                messages.addObject().put("role", "user").putArray("content").addObject().put("type", "text")
                        .put("text", "(conversation continues)");
                lastRole = "user";
            }
            if (!role.equals(lastRole)) {
                ObjectNode message = messages.addObject();
                message.put("role", role);
                blocks = message.putArray("content");
                lastRole = role;
            }
            for (Attachment a : m.attachments()) {
                if (a.kind() == Attachment.Kind.IMAGE && IMAGE_TYPES.contains(a.mimeType())) {
                    ObjectNode source = blocks.addObject().put("type", "image").putObject("source");
                    source.put("type", "base64").put("media_type", a.mimeType()).put("data", a.data());
                }
            }
            String text = m.role() == Role.TOOL ? OpenAiModelProvider.toolResult(m) : m.contentForModel();
            blocks.addObject().put("type", "text").put("text", text.isBlank() ? "(empty)" : text);
        }
        if (!system.isEmpty()) {
            body.put("system", system.toString());
        }
        return body;
    }
}
