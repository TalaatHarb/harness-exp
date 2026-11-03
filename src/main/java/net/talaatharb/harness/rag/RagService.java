package net.talaatharb.harness.rag;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.google.inject.name.Named;

import net.talaatharb.harness.attachments.AttachmentLoader;
import net.talaatharb.harness.config.HarnessSettings;
import net.talaatharb.harness.config.SettingsStore;
import net.talaatharb.harness.llm.ModelSettings;
import net.talaatharb.harness.llm.OllamaModelProvider;

/**
 * Per-conversation RAG: chunks and embeds documents into {@link RagStore}, and finds the passages most similar to a
 * query within one conversation's documents. Documents can be copied between conversations.
 */
@Singleton
public class RagService implements AutoCloseable {
    public static final String RAG_INDEX = "ragIndexPath";
    public static final int MAX_FOLDER_FILES = 500;
    private static final Logger LOG = LoggerFactory.getLogger(RagService.class);
    private static final int EMBED_BATCH = 16;

    /** Progress of long operations; {@code fraction} is 0..1. */
    @FunctionalInterface
    public interface Progress {
        Progress NONE = (message, fraction) -> { };

        void update(String message, double fraction);
    }

    public record Hit(RagDocument document, int index, String text, double score) {
    }

    /**
     * @param documents     documents in the conversation
     * @param otherEmbedder documents indexed with a different embedder, not searched until re-indexed
     */
    public record SearchResult(List<Hit> hits, int documents, List<RagDocument> otherEmbedder) {
    }

    private record CachedQuery(String embedder, String query, float[] vector) {
    }

    private final Supplier<HarnessSettings> settings;
    private final RagStore store;
    private final Function<HarnessSettings, Embedder> embedders;
    private volatile CachedQuery lastQuery;

    @Inject
    public RagService(SettingsStore settingsStore, @Named(RAG_INDEX) Path db) {
        this(settingsStore::current, new RagStore(db), RagService::embedderFor);
    }

    public RagService(Supplier<HarnessSettings> settings, RagStore store, Function<HarnessSettings, Embedder> embedders) {
        this.settings = settings;
        this.store = store;
        this.embedders = embedders;
    }

    public RagSettings settings() {
        RagSettings s = settings.get().rag;
        return s == null ? new RagSettings() : s;
    }

    public RagStore store() {
        return store;
    }

    public Embedder embedder() {
        return embedders.apply(settings.get());
    }

    /** The embedder configured in {@code s.rag}. */
    public static Embedder embedderFor(HarnessSettings s) {
        RagSettings rag = s.rag == null ? new RagSettings() : s.rag;
        if (rag.embedder == RagSettings.EmbedderType.BUILT_IN) {
            return new HashingEmbedder();
        }
        ModelSettings model = s.model == null ? new ModelSettings() : s.model;
        boolean ollamaChat = OllamaModelProvider.ID.equals(model.provider);
        String base = !blank(rag.embeddingBaseUrl) ? rag.embeddingBaseUrl
                : ollamaChat && !blank(model.baseUrl) ? model.baseUrl : ModelSettings.LOCAL_OLLAMA_URL;
        String key = ollamaChat && !blank(model.apiKey) ? model.apiKey : System.getenv("OLLAMA_API_KEY");
        return new OllamaEmbedder(base, rag.embeddingModel, key,
                Duration.ofSeconds(Math.max(10, model.requestTimeoutSeconds)));
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    private static String requireConversation(String conversationId) {
        if (blank(conversationId)) {
            throw new IllegalStateException("No conversation is open");
        }
        return conversationId;
    }

    /** Chunks, embeds and stores {@code text} as a document of the conversation. */
    public RagDocument ingestText(String conversationId, String name, String source, String text, Progress progress)
            throws IOException {
        requireConversation(conversationId);
        if (blank(text)) {
            throw new IllegalArgumentException("Nothing to index: the text is empty");
        }
        Progress p = progress == null ? Progress.NONE : progress;
        RagSettings rag = settings();
        String title = blank(name) ? "Untitled" : name.strip();
        List<String> pieces = TextChunker.chunk(text, rag.chunkSize, rag.chunkOverlap);
        Embedder embedder = embedder();
        List<RagStore.StoredChunk> chunks = embed(embedder, title, pieces, p);
        RagDocument doc = new RagDocument();
        doc.id = newId(conversationId, title);
        doc.conversationId = conversationId;
        doc.name = title;
        doc.source = blank(source) ? "text" : source;
        doc.chars = text.length();
        doc.chunks = chunks.size();
        doc.embedder = embedder.id();
        doc.dimensions = chunks.isEmpty() ? 0 : chunks.get(0).vector().length;
        doc.createdAt = Instant.now();
        store.put(doc, chunks);
        LOG.info("Indexed '{}' into conversation {}: {} chars, {} chunks, {}", title, conversationId, doc.chars,
                doc.chunks, doc.embedder);
        return doc;
    }

    /** Indexes a markdown/text or PDF file under its file name. */
    public RagDocument ingestFile(String conversationId, Path file, Progress progress) throws IOException {
        return ingestFile(conversationId, file, null, progress);
    }

    /** @param name document name; blank = the file name */
    public RagDocument ingestFile(String conversationId, Path file, String name, Progress progress)
            throws IOException {
        long maxBytes = (long) Math.max(1, settings().maxFileMb) * 1024 * 1024;
        String text = AttachmentLoader.extractText(file, maxBytes);
        return ingestText(conversationId, blank(name) ? String.valueOf(file.getFileName()) : name,
                file.toAbsolutePath().toString(), text, progress);
    }

    /** The markdown/text and PDF files under {@code folder} (recursive, sorted, at most {@link #MAX_FOLDER_FILES}). */
    public static List<Path> indexableFiles(Path folder) throws IOException {
        try (Stream<Path> walk = Files.walk(folder, 12)) {
            List<Path> files = walk.filter(Files::isRegularFile).filter(AttachmentLoader::hasText)
                    .filter(f -> !f.getFileName().toString().startsWith(".")).sorted().toList();
            if (files.size() > MAX_FOLDER_FILES) {
                LOG.warn("{} has {} indexable files; only the first {} are used", folder, files.size(),
                        MAX_FOLDER_FILES);
                return files.subList(0, MAX_FOLDER_FILES);
            }
            return files;
        }
    }

    private static List<RagStore.StoredChunk> embed(Embedder embedder, String name, List<String> pieces,
            Progress progress) throws IOException {
        List<RagStore.StoredChunk> chunks = new ArrayList<>(pieces.size());
        for (int start = 0; start < pieces.size(); start += EMBED_BATCH) {
            if (Thread.currentThread().isInterrupted()) {
                throw new InterruptedIOException("Indexing cancelled");
            }
            progress.update("Embedding " + name + " (" + start + "/" + pieces.size() + " parts)",
                    (double) start / pieces.size());
            List<String> batch = pieces.subList(start, Math.min(pieces.size(), start + EMBED_BATCH));
            List<float[]> vectors = embedder.embed(batch);
            if (vectors.size() != batch.size()) {
                throw new IOException("The embedder returned " + vectors.size() + " vectors for " + batch.size()
                        + " texts");
            }
            for (int i = 0; i < batch.size(); i++) {
                float[] v = vectors.get(i);
                if (v.length == 0 || (!chunks.isEmpty() && chunks.get(0).vector().length != v.length)) {
                    throw new IOException("The embedder returned vectors of inconsistent size");
                }
                chunks.add(new RagStore.StoredChunk(start + i, batch.get(i), v));
            }
        }
        progress.update("Embedded " + name, 1);
        return chunks;
    }

    private String newId(String conversationId, String name) {
        String slug = name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("(^-+|-+$)", "");
        if (slug.length() > 40) {
            slug = slug.substring(0, 40).replaceAll("-+$", "");
        }
        if (slug.isEmpty()) {
            slug = "doc";
        }
        String id;
        do {
            id = slug + "-" + String.format("%06x", ThreadLocalRandom.current().nextInt(0x1000000));
        } while (store.document(conversationId, id).isPresent());
        return id;
    }

    public List<RagDocument> documents(String conversationId) {
        return blank(conversationId) ? List.of() : store.documents(conversationId);
    }

    public boolean delete(String conversationId, String documentId) {
        boolean removed = store.delete(requireConversation(conversationId), documentId);
        if (removed) {
            LOG.info("Removed document {} from conversation {}", documentId, conversationId);
        }
        return removed;
    }

    public int deleteConversation(String conversationId) {
        int removed = blank(conversationId) ? 0 : store.deleteConversation(conversationId);
        if (removed > 0) {
            LOG.info("Removed {} RAG document(s) of deleted conversation {}", removed, conversationId);
        }
        return removed;
    }

    /** Removes the documents of conversations not in {@code keep} (e.g. never saved, or deleted elsewhere). */
    public int prune(Collection<String> keep) {
        int removed = 0;
        for (String id : store.conversations()) {
            if (!keep.contains(id)) {
                removed += deleteConversation(id);
            }
        }
        return removed;
    }

    /**
     * Copies documents (chunks and vectors) into another conversation.
     *
     * @return the copies
     */
    public List<RagDocument> copy(String fromConversation, Collection<String> documentIds, String toConversation)
            throws IOException {
        requireConversation(fromConversation);
        requireConversation(toConversation);
        if (fromConversation.equals(toConversation)) {
            throw new IllegalArgumentException("Pick a different conversation to copy to");
        }
        List<RagDocument> copies = new ArrayList<>();
        for (String id : documentIds) {
            RagDocument source = store.document(fromConversation, id)
                    .orElseThrow(() -> new IllegalArgumentException("No document " + id));
            RagDocument copy = source.copy();
            copy.conversationId = toConversation;
            copy.id = store.document(toConversation, id).isPresent() ? newId(toConversation, source.name) : id;
            copy.createdAt = Instant.now();
            store.put(copy, store.chunks(fromConversation, id));
            copies.add(copy);
        }
        LOG.info("Copied {} document(s) from conversation {} to {}", copies.size(), fromConversation,
                toConversation);
        return copies;
    }

    /**
     * Re-embeds the conversation's documents that were indexed with a different embedder (keeps the chunk text).
     *
     * @return the number of documents re-embedded
     */
    public int reindex(String conversationId, Progress progress) throws IOException {
        Progress p = progress == null ? Progress.NONE : progress;
        Embedder embedder = embedder();
        int count = 0;
        for (RagDocument doc : documents(conversationId)) {
            if (embedder.id().equals(doc.embedder)) {
                continue;
            }
            List<RagStore.StoredChunk> old = store.chunks(conversationId, doc.id);
            List<RagStore.StoredChunk> fresh = embed(embedder, doc.name,
                    old.stream().map(RagStore.StoredChunk::text).toList(), p);
            RagDocument updated = doc.copy();
            updated.embedder = embedder.id();
            updated.dimensions = fresh.isEmpty() ? 0 : fresh.get(0).vector().length;
            store.put(updated, fresh);
            count++;
        }
        if (count > 0) {
            LOG.info("Re-indexed {} document(s) of conversation {} with {}", count, conversationId, embedder.id());
        }
        return count;
    }

    /** The passages most similar to {@code query} in the conversation's documents, best first. */
    public SearchResult search(String conversationId, String query, int limit, double minScore) throws IOException {
        List<RagDocument> docs = documents(conversationId);
        if (docs.isEmpty() || blank(query) || limit <= 0) {
            return new SearchResult(List.of(), docs.size(), List.of());
        }
        Embedder embedder = embedder();
        Map<String, RagDocument> compatible = new HashMap<>();
        List<RagDocument> other = new ArrayList<>();
        for (RagDocument d : docs) {
            if (embedder.id().equals(d.embedder)) {
                compatible.put(d.id, d);
            } else {
                other.add(d);
            }
        }
        if (compatible.isEmpty()) {
            return new SearchResult(List.of(), docs.size(), other);
        }
        float[] q = embedQuery(embedder, query.strip());
        List<Hit> hits = new ArrayList<>();
        for (RagStore.ScoredChunk c : store.search(conversationId, embedder.id(), q, limit)) {
            RagDocument d = compatible.get(c.documentId());
            if (d != null && c.score() >= minScore) {
                hits.add(new Hit(d, c.index(), c.text(), c.score()));
            }
        }
        return new SearchResult(List.copyOf(hits), docs.size(), other);
    }

    /** Embeds a query, reusing the last result (the context stage asks again on every loop iteration). */
    private float[] embedQuery(Embedder embedder, String query) throws IOException {
        CachedQuery cached = lastQuery;
        if (cached != null && cached.embedder().equals(embedder.id()) && cached.query().equals(query)) {
            return cached.vector();
        }
        float[] vector = embedder.embed(List.of(query)).get(0);
        lastQuery = new CachedQuery(embedder.id(), query, vector);
        return vector;
    }

    /** Short label for a passage, e.g. {@code handbook.pdf, part 3/12}. */
    public static String label(Hit hit) {
        return hit.document().name + ", part " + (hit.index() + 1) + "/" + hit.document().chunks;
    }

    /** Conversations with documents (for pruning checks). */
    public Set<String> conversations() {
        return store.conversations();
    }

    @Override
    public void close() {
        store.close();
    }
}
