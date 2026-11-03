package net.talaatharb.harness.rag;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** Retrieval-augmented generation over per-conversation documents (RAG tab). */
@JsonIgnoreProperties(ignoreUnknown = true)
public class RagSettings {
    public static final String DEFAULT_OLLAMA_MODEL = "nomic-embed-text";

    public enum EmbedderType {
        /** Ollama {@code /api/embed} with an embedding model, e.g. nomic-embed-text. */
        OLLAMA,
        /** Offline hashed bag-of-words vectors: no model needed, keyword-level matching only. */
        BUILT_IN
    }

    /** Add the passages relevant to each message to the context (the 'rag' context stage). */
    public boolean autoContext = true;
    public EmbedderType embedder = EmbedderType.OLLAMA;
    public String embeddingModel = DEFAULT_OLLAMA_MODEL;
    /** Blank = the Model tab's URL when the provider is Ollama, otherwise the local Ollama. */
    public String embeddingBaseUrl = "";
    /** Target chunk length in characters. */
    public int chunkSize = 1200;
    /** Characters repeated from the end of the previous chunk so ideas cut at a boundary stay searchable. */
    public int chunkOverlap = 200;
    /** Passages added to the context per message. */
    public int contextResults = 4;
    /** Upper bound for the rag_search tool's {@code limit}. */
    public int maxSearchResults = 8;
    /** Passages scoring below this cosine similarity (in %) are dropped. */
    public int minScorePercent = 25;
    /** Cap on the retrieved text added to the context. */
    public int maxContextChars = 6000;
    /** Largest file accepted for indexing. */
    public int maxFileMb = 50;
}
