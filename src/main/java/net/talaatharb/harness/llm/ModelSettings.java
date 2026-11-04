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
    /** Null leaves the provider default unchanged. Anthropic accepts 0..1, OpenAI 0..2, Ollama >= 0. */
    public Double temperature;
    /**
     * Optional signed 64-bit sampling seed. Ollama requires the signed 32-bit range supported by ollama4j.
     * Anthropic rejects a configured seed. Seeds are best-effort reproducibility, not a determinism guarantee.
     * OpenAI-compatible servers and individual models may reject either sampling option.
     */
    public Long seed;

    public void validateSampling(String providerId) throws ModelProviderException {
        double max = AnthropicModelProvider.ID.equals(providerId) ? 1
                : OpenAiModelProvider.ID.equals(providerId) ? 2 : Float.MAX_VALUE;
        if (temperature != null && (!Double.isFinite(temperature) || temperature < 0 || temperature > max)) {
            throw new ModelProviderException("Temperature for " + providerId + " must be finite and between 0 and "
                    + max + " (or blank for the provider default)");
        }
        if (AnthropicModelProvider.ID.equals(providerId) && seed != null) {
            throw new ModelProviderException("Anthropic does not support seed; clear it in Model settings");
        }
        if (OllamaModelProvider.ID.equals(providerId) && seed != null
                && (seed < Integer.MIN_VALUE || seed > Integer.MAX_VALUE)) {
            throw new ModelProviderException("Ollama seed must be between -2147483648 and 2147483647 "
                    + "(the range supported by ollama4j)");
        }
    }

    public static Double parseTemperature(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            double value = Double.parseDouble(text.strip());
            if (!Double.isFinite(value) || value < 0) {
                throw new NumberFormatException();
            }
            return value;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Temperature must be a finite nonnegative number, or blank");
        }
    }

    public static Long parseSeed(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            return Long.valueOf(text.strip());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Seed must be a signed 64-bit integer, or blank");
        }
    }
}
