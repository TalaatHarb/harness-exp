package net.talaatharb.harness.memory;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** Memory tab settings. Where the data lives is in {@code storage.memoryDir} / {@code storage.memoryDb}. */
@JsonIgnoreProperties(ignoreUnknown = true)
public class MemorySettings {
    public enum Backend {
        /** One {@code <id>.md} file per memory in the memory folder (easy to read, edit and sync). */
        MARKDOWN,
        /** Entries in a MapDB file. */
        MAPDB
    }

    public Backend backend = Backend.MARKDOWN;
    /** Most memories the context builder injects for one message (only those relevant to it). */
    public int maxContextMemories = 5;
    /** Most results returned by one {@code recall} call. */
    public int maxRecallResults = 10;
    /**
     * How much of a new memory must already be covered by an existing one (word overlap, %) for {@code remember} to
     * treat it as a duplicate instead of adding it.
     */
    public int duplicateThresholdPercent = 80;
}
