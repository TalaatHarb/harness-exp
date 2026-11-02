package net.talaatharb.harness.llm;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** Model/provider connection settings. */
@JsonIgnoreProperties(ignoreUnknown = true)
public class ModelSettings {
    public static final String LOCAL_OLLAMA_URL = "http://localhost:11434";
    public static final String CLOUD_OLLAMA_URL = "https://ollama.com";

    public String provider = OllamaModelProvider.ID;
    /** Use {@link #LOCAL_OLLAMA_URL} for a local daemon or {@link #CLOUD_OLLAMA_URL} with an API key. */
    public String baseUrl = LOCAL_OLLAMA_URL;
    /** Optional bearer token (required for Ollama cloud). Stored in plain text in the settings file. */
    public String apiKey = "";
    public String model = "qwen2.5:3b";
    public long requestTimeoutSeconds = 180;
    public boolean streaming = true;
}
