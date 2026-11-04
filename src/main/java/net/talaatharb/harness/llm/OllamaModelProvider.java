package net.talaatharb.harness.llm;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.inject.Singleton;

import io.github.ollama4j.Ollama;
import io.github.ollama4j.models.chat.OllamaChatMessage;
import io.github.ollama4j.models.chat.OllamaChatMessageRole;
import io.github.ollama4j.models.chat.OllamaChatRequest;
import io.github.ollama4j.models.chat.OllamaChatResult;
import io.github.ollama4j.models.chat.OllamaChatStreamObserver;
import io.github.ollama4j.models.response.Model;
import io.github.ollama4j.utils.OptionsBuilder;

/**
 * Ollama-backed provider. Works with a local daemon or with Ollama cloud when
 * a base URL and API key are configured. Tool calling is driven by the harness
 * itself (see {@code ToolCallParser}), so ollama4j's internal tool loop is disabled.
 */
@Singleton
public class OllamaModelProvider implements ModelProvider {
    public static final String ID = "ollama";
    private static final Logger LOG = LoggerFactory.getLogger(OllamaModelProvider.class);

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String displayName() {
        return "Ollama (local / cloud)";
    }

    @Override
    public String defaultBaseUrl() {
        return ModelSettings.LOCAL_OLLAMA_URL;
    }

    @Override
    public String apiKeyEnvVar() {
        return "OLLAMA_API_KEY";
    }

    private static Ollama client(ModelSettings config) {
        Ollama ollama = new Ollama(config.baseUrl);
        ollama.setRequestTimeoutSeconds(config.requestTimeoutSeconds);
        String key = HttpJson.apiKey(config, "OLLAMA_API_KEY");
        if (!key.isEmpty()) {
            ollama.setBearerAuth(key);
        }
        return ollama;
    }

    @Override
    public boolean ping(ModelSettings config) {
        try {
            return client(config).ping();
        } catch (Exception e) {
            LOG.warn("Ollama ping to {} failed", config.baseUrl, e);
            return false;
        }
    }

    @Override
    public List<String> listModels(ModelSettings config) throws ModelProviderException {
        try {
            return client(config).listModels().stream().map(Model::getName).sorted().toList();
        } catch (Exception e) {
            throw new ModelProviderException("Could not list Ollama models: " + e.getMessage(), e);
        }
    }

    @Override
    public ModelReply chat(ModelSettings config, ModelRequest request, Consumer<String> tokenSink)
            throws ModelProviderException {
        long start = System.currentTimeMillis();
        config.validateSampling(ID);
        List<OllamaChatMessage> messages = new ArrayList<>();
        for (ChatMessage m : request.messages()) {
            OllamaChatMessage message = new OllamaChatMessage(toOllamaRole(m.role()), m.contentForModel());
            List<byte[]> images = m.images();
            if (!images.isEmpty()) {
                message.setImages(new ArrayList<>(images));
            }
            messages.add(message);
        }
        OllamaChatRequest chatRequest = OllamaChatRequest.builder()
                .withModel(request.model())
                .withMessages(messages)
                .withUseTools(false);
        if (config.temperature != null || config.seed != null) {
            OptionsBuilder options = new OptionsBuilder();
            if (config.temperature != null) {
                options.setTemperature(config.temperature.floatValue());
            }
            if (config.seed != null) {
                options.setSeed(Math.toIntExact(config.seed));
            }
            chatRequest.withOptions(options.build());
        }
        StringBuilder streamed = new StringBuilder();
        OllamaChatStreamObserver observer = null;
        if (request.stream()) {
            chatRequest.withStreaming();
            observer = new OllamaChatStreamObserver(thinking -> { }, token -> {
                streamed.append(token);
                if (tokenSink != null) {
                    tokenSink.accept(token);
                }
            });
        }
        try {
            OllamaChatResult result = client(config).chat(chatRequest.build(), observer);
            String content = null;
            String thinking = null;
            if (result != null && result.getResponseModel() != null
                    && result.getResponseModel().getMessage() != null) {
                content = result.getResponseModel().getMessage().getResponse();
                thinking = result.getResponseModel().getMessage().getThinking();
            }
            if ((content == null || content.isEmpty()) && streamed.length() > 0) {
                content = streamed.toString();
            }
            return new ModelReply(content, thinking, System.currentTimeMillis() - start);
        } catch (Exception e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new ModelProviderException("Ollama chat failed: " + e.getMessage(), e);
        }
    }

    static OllamaChatMessageRole toOllamaRole(Role role) {
        switch (role) {
            case SYSTEM:
                return OllamaChatMessageRole.SYSTEM;
            case ASSISTANT:
                return OllamaChatMessageRole.ASSISTANT;
            case TOOL:
                return OllamaChatMessageRole.TOOL;
            case USER:
            default:
                return OllamaChatMessageRole.USER;
        }
    }
}
