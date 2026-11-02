package net.talaatharb.harness.llm;

import java.util.List;
import java.util.function.Consumer;

/**
 * Abstraction over a local or cloud model backend. The harness only talks to
 * models through this interface so new providers can be added via Guice
 * multibindings without touching the agent loop. Connection settings are passed
 * per call so a run uses one consistent snapshot.
 */
public interface ModelProvider {

    /** Stable identifier persisted in settings (e.g. {@code ollama}). */
    String id();

    String displayName();

    /** Base URL used when the settings field is blank or when switching to this provider. */
    default String defaultBaseUrl() {
        return "";
    }

    /** Environment variable read when the API key field is blank ({@code null} if none). */
    default String apiKeyEnvVar() {
        return null;
    }

    boolean ping(ModelSettings config);

    List<String> listModels(ModelSettings config) throws ModelProviderException;

    /**
     * Sends the request to the model.
     *
     * @param tokenSink receives streamed response deltas when {@link ModelRequest#stream()} is set
     */
    ModelReply chat(ModelSettings config, ModelRequest request, Consumer<String> tokenSink)
            throws ModelProviderException;
}
