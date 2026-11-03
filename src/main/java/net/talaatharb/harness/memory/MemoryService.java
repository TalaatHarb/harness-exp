package net.talaatharb.harness.memory;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.google.inject.name.Named;

import net.talaatharb.harness.config.SettingsStore;

/**
 * Long-term memory on top of the configured backend (markdown folder or MapDB file). {@link #remember} avoids
 * near-duplicates; {@link #recall} ranks memories by word overlap with the query (rare words count more).
 */
@Singleton
public class MemoryService implements AutoCloseable {
    public static final String MEMORY_DIR = "memoryDir";
    public static final String MEMORY_DB = "memoryDbPath";
    /** Longest accepted memory text. Memories are meant to be short facts. */
    public static final int MAX_MEMORY_CHARS = 4_000;
    static final Comparator<MemoryEntry> NEWEST_FIRST = Comparator.comparing((MemoryEntry m) -> m.updatedAt,
            Comparator.nullsLast(Comparator.reverseOrder())).thenComparing(m -> m.id, Comparator.nullsLast(
                    Comparator.naturalOrder()));
    private static final Logger LOG = LoggerFactory.getLogger(MemoryService.class);
    private static final Set<String> STOP_WORDS = Set.of("a", "an", "the", "and", "or", "but", "of", "to", "in", "on",
            "at", "for", "with", "by", "from", "as", "is", "are", "was", "were", "be", "been", "being", "am", "it",
            "its", "this", "that", "these", "those", "i", "me", "my", "you", "your", "we", "our", "they", "their",
            "he", "she", "his", "her", "them", "do", "does", "did", "have", "has", "had", "not", "no", "so", "if",
            "then", "than", "there", "what", "which", "who", "whom", "when", "where", "why", "how", "can", "could",
            "should", "would", "will", "shall", "may", "might", "must", "about", "into", "also", "just", "very",
            "some", "any", "all", "s", "t", "re", "ve", "ll", "d", "m");

    public enum Outcome {
        ADDED, UPDATED, DUPLICATE
    }

    /** @param previous the memory before the change, for {@link Outcome#UPDATED} and {@link Outcome#DUPLICATE} */
    public record RememberResult(Outcome outcome, MemoryEntry entry, MemoryEntry previous) {
    }

    public record Match(MemoryEntry entry, double score) {
    }

    private final Supplier<MemorySettings> settings;
    private final MemoryStore markdown;
    private final MemoryStore mapdb;

    @Inject
    public MemoryService(SettingsStore store, @Named(MEMORY_DIR) Path dir, @Named(MEMORY_DB) Path db) {
        this(() -> store.current().memory, new MarkdownMemoryStore(dir), new MapDbMemoryStore(db));
    }

    public MemoryService(Supplier<MemorySettings> settings, MemoryStore markdown, MemoryStore mapdb) {
        this.settings = settings;
        this.markdown = markdown;
        this.mapdb = mapdb;
    }

    public MemorySettings settings() {
        MemorySettings s = settings.get();
        return s == null ? new MemorySettings() : s;
    }

    public MemoryStore store(MemorySettings.Backend backend) {
        return backend == MemorySettings.Backend.MAPDB ? mapdb : markdown;
    }

    /** The backend selected in the applied settings; used by the tools and the context builder. */
    public MemoryStore active() {
        return store(settings().backend);
    }

    public RememberResult remember(String text, List<String> tags, String replaceId, String source)
            throws IOException {
        return remember(active(), text, tags, replaceId, source);
    }

    /**
     * Adds a memory unless an existing one already says the same thing.
     * <ul>
     * <li>{@code replaceId} given: that memory's text is replaced (tags merged).</li>
     * <li>An existing memory covers the new text: nothing is added (new tags are merged in) → DUPLICATE.</li>
     * <li>The new text covers an existing memory (it restates and extends it): that memory is updated → UPDATED.</li>
     * <li>Otherwise a new memory is added → ADDED.</li>
     * </ul>
     */
    public RememberResult remember(MemoryStore store, String text, List<String> tags, String replaceId, String source)
            throws IOException {
        String clean = text == null ? "" : text.strip();
        if (clean.isEmpty()) {
            throw new IllegalArgumentException("The memory text is empty");
        }
        if (clean.length() > MAX_MEMORY_CHARS) {
            throw new IllegalArgumentException("The memory is too long (" + clean.length() + " > " + MAX_MEMORY_CHARS
                    + " characters); store one short, self-contained fact per memory");
        }
        List<String> newTags = MemoryEntry.cleanTags(tags);
        if (replaceId != null && !replaceId.isBlank()) {
            MemoryEntry old = store.get(replaceId.strip()).orElseThrow(() -> new IllegalArgumentException(
                    "No memory with id '" + replaceId.strip() + "' (use recall to find ids)"));
            MemoryEntry updated = old.copy();
            updated.text = clean;
            updated.tags = merge(old.tags, newTags);
            updated.source = source;
            return new RememberResult(Outcome.UPDATED, store.save(updated), old);
        }
        double threshold = Math.max(0.3, Math.min(1.0, settings().duplicateThresholdPercent / 100.0));
        Set<String> newTerms = terms(clean);
        MemoryEntry covering = null;
        MemoryEntry covered = null;
        double bestCovering = 0;
        double bestCovered = 0;
        for (MemoryEntry e : store.list()) {
            if (normalize(e.text).equals(normalize(clean))) {
                covering = e;
                bestCovering = 2;
                break;
            }
            Set<String> oldTerms = terms(e.text);
            if (newTerms.isEmpty() || oldTerms.isEmpty()) {
                continue;
            }
            double newInOld = containment(newTerms, oldTerms);
            double oldInNew = containment(oldTerms, newTerms);
            if (newInOld >= threshold && newInOld > bestCovering) {
                covering = e;
                bestCovering = newInOld;
            } else if (oldInNew >= threshold && oldInNew > bestCovered) {
                covered = e;
                bestCovered = oldInNew;
            }
        }
        if (covering != null) {
            List<String> merged = merge(covering.tags, newTags);
            if (merged.size() != covering.tags.size()) {
                MemoryEntry withTags = covering.copy();
                withTags.tags = merged;
                return new RememberResult(Outcome.DUPLICATE, store.save(withTags), covering);
            }
            return new RememberResult(Outcome.DUPLICATE, covering, covering);
        }
        if (covered != null) {
            MemoryEntry updated = covered.copy();
            updated.text = clean;
            updated.tags = merge(covered.tags, newTags);
            updated.source = source;
            return new RememberResult(Outcome.UPDATED, store.save(updated), covered);
        }
        MemoryEntry saved = store.save(new MemoryEntry(clean, newTags, source));
        LOG.info("Remembered {} in {}", saved.id, store.location());
        return new RememberResult(Outcome.ADDED, saved, null);
    }

    public List<Match> recall(String query, int limit) {
        return rank(active().list(), query, limit);
    }

    /**
     * Ranks by the share of the query's (idf-weighted) terms found in a memory's text and tags, with a bonus for tag
     * hits and for containing the whole query. Terms present in more than half of the memories are ignored when there
     * are a few memories, as they don't tell memories apart. A blank query returns the most recent memories.
     */
    public static List<Match> rank(List<MemoryEntry> all, String query, int limit) {
        int max = Math.max(1, limit);
        Set<String> queryTerms = terms(query);
        if (queryTerms.isEmpty()) {
            return all.stream().sorted(NEWEST_FIRST).limit(max).map(e -> new Match(e, 0)).toList();
        }
        Map<MemoryEntry, Set<String>> termsOf = new HashMap<>();
        Map<String, Integer> df = new HashMap<>();
        for (MemoryEntry e : all) {
            Set<String> t = terms(e.text + " " + String.join(" ", e.tags));
            termsOf.put(e, t);
            t.forEach(term -> df.merge(term, 1, Integer::sum));
        }
        int n = all.size();
        Map<String, Double> idf = new HashMap<>();
        double total = 0;
        for (String q : queryTerms) {
            int d = df.getOrDefault(q, 0);
            double w = n >= 4 && d > n / 2.0 ? 0 : Math.log(1 + (n + 1.0) / (d + 1.0));
            idf.put(q, w);
            total += w;
        }
        String phrase = normalize(query);
        List<Match> matches = new ArrayList<>();
        for (MemoryEntry e : all) {
            Set<String> t = termsOf.get(e);
            double covered = 0;
            for (String q : queryTerms) {
                if (t.contains(q)) {
                    covered += idf.get(q);
                }
            }
            if (covered <= 0) {
                continue;
            }
            double score = total > 0 ? covered / total : 0;
            Set<String> tagTerms = terms(String.join(" ", e.tags));
            score += 0.25 * queryTerms.stream().filter(tagTerms::contains).count();
            if (phrase.length() > 3 && normalize(e.text).contains(phrase)) {
                score += 0.5;
            }
            matches.add(new Match(e, score));
        }
        matches.sort(Comparator.comparingDouble(Match::score).reversed()
                .thenComparing(Match::entry, NEWEST_FIRST));
        return matches.stream().limit(max).toList();
    }

    /** Lower-case word stems without stop words, e.g. {@code "The user's cats"} → {@code [user, cat]}. */
    public static Set<String> terms(String text) {
        Set<String> terms = new LinkedHashSet<>();
        if (text == null) {
            return terms;
        }
        for (String word : text.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}]+")) {
            if (word.isEmpty() || STOP_WORDS.contains(word)) {
                continue;
            }
            terms.add(stem(word));
        }
        return terms;
    }

    private static String stem(String w) {
        if (w.length() > 4 && w.endsWith("ies")) {
            return w.substring(0, w.length() - 3) + "y";
        }
        if (w.length() > 3 && w.endsWith("s") && !w.endsWith("ss") && !w.endsWith("us")) {
            return w.substring(0, w.length() - 1);
        }
        return w;
    }

    /** Share of {@code a} found in {@code b}. */
    static double containment(Set<String> a, Set<String> b) {
        if (a.isEmpty()) {
            return 0;
        }
        return a.stream().filter(b::contains).count() / (double) a.size();
    }

    private static String normalize(String text) {
        return text == null ? "" : text.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]+", " ").strip();
    }

    private static List<String> merge(List<String> a, List<String> b) {
        List<String> all = new ArrayList<>(a == null ? List.of() : a);
        all.addAll(b);
        return MemoryEntry.cleanTags(all);
    }

    /** One line per memory for the model: {@code - [id] text (tags: a, b; updated 2026-11-03)}. */
    public static String describe(MemoryEntry e) {
        String text = e.text.strip().replace("\n", "\n  ");
        StringBuilder sb = new StringBuilder("- [").append(e.id).append("] ").append(text);
        List<String> extra = new ArrayList<>();
        if (!e.tags.isEmpty()) {
            extra.add("tags: " + String.join(", ", e.tags));
        }
        if (e.updatedAt != null) {
            extra.add("updated " + e.updatedAt.toString().substring(0, 10));
        }
        if (!extra.isEmpty()) {
            sb.append(" (").append(String.join("; ", extra)).append(')');
        }
        return sb.toString();
    }

    @Override
    public void close() {
        markdown.close();
        mapdb.close();
    }
}
