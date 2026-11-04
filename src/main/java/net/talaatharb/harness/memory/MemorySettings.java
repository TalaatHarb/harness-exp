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
    /** Route unambiguous single-sentence "Remember that ..." requests through the approved tool verbatim. */
    public boolean directRememberRequests = true;
    /** Most memories the context builder injects for one message (only those relevant to it). */
    public int maxContextMemories = 5;
    /** Most results returned by one {@code recall} call. */
    public int maxRecallResults = 10;
    /**
     * Legacy setting retained for settings-file compatibility. Word-overlap deduplication is no longer used:
     * distinct facts can differ by only one name, number or negation.
     */
    public int duplicateThresholdPercent = 80;
}
