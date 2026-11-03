package net.talaatharb.harness.memory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** One remembered fact, preference or note. Stored as a markdown file or a MapDB entry. */
@JsonIgnoreProperties(ignoreUnknown = true)
public class MemoryEntry {
    public static final String SOURCE_USER = "user";
    public static final String SOURCE_AGENT = "agent";

    /** Assigned by the store on first save. */
    public String id;
    public String text = "";
    public List<String> tags = new ArrayList<>();
    /** Who wrote it: {@code user} (memory tab) or {@code agent} (remember tool). */
    public String source = SOURCE_USER;
    public Instant createdAt;
    public Instant updatedAt;

    public MemoryEntry() {
    }

    public MemoryEntry(String text, List<String> tags, String source) {
        this.text = text == null ? "" : text.strip();
        this.tags = cleanTags(tags);
        this.source = source;
    }

    public MemoryEntry copy() {
        MemoryEntry c = new MemoryEntry(text, tags, source);
        c.id = id;
        c.createdAt = createdAt;
        c.updatedAt = updatedAt;
        return c;
    }

    /** First line, shortened; for lists. */
    public String preview(int max) {
        String first = text == null ? "" : text.strip().split("\n", 2)[0].strip();
        return first.length() <= max ? first : first.substring(0, Math.max(0, max - 1)).strip() + "…";
    }

    /** Lower-case, trimmed, without {@code #}, unique, non-blank. */
    public static List<String> cleanTags(List<String> tags) {
        if (tags == null) {
            return new ArrayList<>();
        }
        return new ArrayList<>(tags.stream().filter(t -> t != null && !t.isBlank())
                .map(t -> t.strip().replaceFirst("^#", "").toLowerCase(java.util.Locale.ROOT))
                .filter(t -> !t.isBlank()).distinct().toList());
    }

    /** Parses {@code "a, b #c"}-style text. */
    public static List<String> parseTags(String text) {
        if (text == null) {
            return new ArrayList<>();
        }
        return cleanTags(List.of(text.split("[,\\s]+")));
    }
}
