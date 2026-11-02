package net.talaatharb.harness.llm;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** Model/provider connection settings. */
@JsonIgnoreProperties(ignoreUnknown = true)
public class ModelSettings {
    public static final String LOCAL_OLLAMA_URL = "http://localhost:11434";
    public static final String CLOUD_OLLAMA_URL = "https://ollama.com";
    public static final int DEFAULT_MAX_OUTPUT_TOKENS = 4096;

    /** {@code ollama}, {@code openai} or {@code anthropic} (see the bound {@link ModelProvider}s). */
    public String provider = OllamaModelProvider.ID;
    /** Provider endpoint; blank means the provider's default (e.g. {@link #LOCAL_OLLAMA_URL}). */
    public String baseUrl = LOCAL_OLLAMA_URL;
    /**
     * API key (Ollama cloud, OpenAI, Anthropic). Stored in plain text in the settings file; leave blank to use the
     * provider's environment variable instead ({@code OLLAMA_API_KEY}, {@code OPENAI_API_KEY}, {@code ANTHROPIC_API_KEY}).
     */
    public String apiKey = "";
    public String model = "qwen2.5:3b";
    public long requestTimeoutSeconds = 180;
    public boolean streaming = true;
    /** Reply length cap; required by Anthropic, unused by the other providers. */
    public int maxOutputTokens = DEFAULT_MAX_OUTPUT_TOKENS;
}
