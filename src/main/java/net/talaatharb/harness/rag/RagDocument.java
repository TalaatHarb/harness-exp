package net.talaatharb.harness.rag;

import java.time.Instant;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** A source (file or text) indexed into one conversation's knowledge base. */
@JsonIgnoreProperties(ignoreUnknown = true)
public class RagDocument {
    public String id;
    public String conversationId;
    public String name;
    /** File path, or e.g. {@code pasted} / {@code agent} for text. */
    public String source;
    public int chars;
    public int chunks;
    /** {@link Embedder#id()} of the stored vectors. */
    public String embedder;
    public int dimensions;
    public Instant createdAt;

    public RagDocument copy() {
        RagDocument d = new RagDocument();
        d.id = id;
        d.conversationId = conversationId;
        d.name = name;
        d.source = source;
        d.chars = chars;
        d.chunks = chunks;
        d.embedder = embedder;
        d.dimensions = dimensions;
        d.createdAt = createdAt;
        return d;
    }
}
