package net.talaatharb.harness.llm;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.inject.Singleton;

/**
 * OpenAI Chat Completions provider. Also works with OpenAI-compatible endpoints (Azure OpenAI proxies, OpenRouter,
 * Groq, LM Studio, vLLM, …) by changing the base URL. The key comes from the settings or {@code OPENAI_API_KEY}.
 */
@Singleton
public class OpenAiModelProvider implements ModelProvider {
    public static final String ID = "openai";
    public static final String DEFAULT_URL = "https://api.openai.com/v1";
    public static final String API_KEY_ENV = "OPENAI_API_KEY";
    private static final Logger LOG = LoggerFactory.getLogger(OpenAiModelProvider.class);

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String displayName() {
        return "OpenAI (or OpenAI-compatible)";
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
        String key = HttpJson.apiKey(config, API_KEY_ENV);
        if (!key.isEmpty()) {
            headers.put("Authorization", "Bearer " + key);
        }
        return headers;
    }

    @Override
    public boolean ping(ModelSettings config) {
        try {
            listModels(config);
            return true;
        } catch (ModelProviderException e) {
            LOG.warn("OpenAI ping failed: {}", e.getMessage());
            return false;
        }
    }

    @Override
    public List<String> listModels(ModelSettings config) throws ModelProviderException {
        JsonNode res = HttpJson.get(HttpJson.url(config, DEFAULT_URL, "/models"), headers(config), config, "OpenAI");
        List<String> models = new ArrayList<>();
        res.path("data").forEach(m -> models.add(m.path("id").asText()));
        return models.stream().filter(s -> !s.isBlank()).sorted().toList();
    }

    @Override
    public ModelReply chat(ModelSettings config, ModelRequest request, Consumer<String> tokenSink)
            throws ModelProviderException {
        long start = System.currentTimeMillis();
        config.validateSampling(ID);
        ObjectNode body = HttpJson.MAPPER.createObjectNode();
        body.put("model", request.model());
        body.set("messages", messages(request.messages()));
        if (config.temperature != null) {
            body.put("temperature", config.temperature);
        }
        if (config.seed != null) {
            body.put("seed", config.seed);
        }
        String url = HttpJson.url(config, DEFAULT_URL, "/chat/completions");
        if (!request.stream()) {
            JsonNode res = HttpJson.post(url, headers(config), body, config, "OpenAI");
            JsonNode message = res.path("choices").path(0).path("message");
            return new ModelReply(message.path("content").asText(""), reasoning(message),
                    System.currentTimeMillis() - start);
        }
        body.put("stream", true);
        StringBuilder content = new StringBuilder();
        StringBuilder thinking = new StringBuilder();
        HttpJson.postStream(url, headers(config), body, config, "OpenAI", chunk -> {
            JsonNode delta = chunk.path("choices").path(0).path("delta");
            String text = delta.path("content").asText("");
            if (!text.isEmpty()) {
                content.append(text);
                if (tokenSink != null) {
                    tokenSink.accept(text);
                }
            }
            String r = reasoning(delta);
            if (r != null) {
                thinking.append(r);
            }
        });
        return new ModelReply(content.toString(), thinking.isEmpty() ? null : thinking.toString(),
                System.currentTimeMillis() - start);
    }

    /** Some compatible servers (DeepSeek, vLLM, OpenRouter) return reasoning text next to the content. */
    private static String reasoning(JsonNode message) {
        for (String field : List.of("reasoning_content", "reasoning")) {
            JsonNode node = message.path(field);
            if (node.isTextual() && !node.asText().isEmpty()) {
                return node.asText();
            }
        }
        return null;
    }

    /**
     * The harness runs its own text-based tool protocol, so tool results are sent as user messages (OpenAI's
     * {@code tool} role requires native tool-call ids).
     */
    static ArrayNode messages(List<ChatMessage> messages) {
        ArrayNode out = HttpJson.MAPPER.createArrayNode();
        for (ChatMessage m : messages) {
            ObjectNode node = out.addObject();
            switch (m.role()) {
                case SYSTEM -> node.put("role", "system");
                case ASSISTANT -> node.put("role", "assistant");
                case TOOL, USER -> node.put("role", "user");
            }
            String text = m.role() == Role.TOOL ? toolResult(m) : m.contentForModel();
            List<Attachment> images = m.attachments().stream().filter(a -> a.kind() == Attachment.Kind.IMAGE)
                    .toList();
            if (images.isEmpty()) {
                node.put("content", text);
                continue;
            }
            ArrayNode parts = node.putArray("content");
            parts.addObject().put("type", "text").put("text", text);
            for (Attachment image : images) {
                parts.addObject().put("type", "image_url").putObject("image_url")
                        .put("url", "data:" + image.mimeType() + ";base64," + image.data());
            }
        }
        return out;
    }

    static String toolResult(ChatMessage m) {
        return "[Tool result" + (m.name() == null ? "" : " from " + m.name()) + "]\n" + m.contentForModel();
    }
}
