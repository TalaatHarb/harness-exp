package net.talaatharb.harness.rag;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.codecs.KnnVectorsFormat;
import org.apache.lucene.codecs.KnnVectorsReader;
import org.apache.lucene.codecs.KnnVectorsWriter;
import org.apache.lucene.codecs.lucene912.Lucene912Codec;
import org.apache.lucene.codecs.lucene99.Lucene99HnswVectorsFormat;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.KnnFloatVectorField;
import org.apache.lucene.document.StoredField;
import org.apache.lucene.document.StringField;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.FloatVectorValues;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.index.SegmentReadState;
import org.apache.lucene.index.SegmentWriteState;
import org.apache.lucene.index.StoredFields;
import org.apache.lucene.index.Term;
import org.apache.lucene.index.VectorSimilarityFunction;
import org.apache.lucene.search.BooleanClause.Occur;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.DocIdSetIterator;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.KnnFloatVectorQuery;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.ScoreMode;
import org.apache.lucene.search.Scorer;
import org.apache.lucene.search.SearcherManager;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.search.Weight;
import org.apache.lucene.store.ByteBuffersDirectory;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;
import org.apache.lucene.util.Bits;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

/**
 * Lucene index holding every conversation's documents and chunk vectors. Each document is one metadata record plus
 * one record per chunk (text, position and an HNSW-indexed vector). Vector search runs as a k-NN query filtered to
 * one conversation and embedder; vectors of different sizes go to different fields ({@code vector_<dims>}).
 */
public class RagStore implements AutoCloseable {
    private static final Logger LOG = LoggerFactory.getLogger(RagStore.class);
    /** Lucene's default limit is 1024; allow larger embedding models (e.g. 1536/3072-dimensional ones). */
    static final int MAX_DIMENSIONS = 4096;
    /**
     * Minimum HNSW candidates per search (like {@code ef}); the best {@code k} of them are returned. When the
     * conversation has few chunks, Lucene's filtered search then visits them all and falls back to an exact scan.
     */
    static final int MIN_CANDIDATES = 100;

    private static final String TYPE = "type";
    private static final String TYPE_DOC = "doc";
    private static final String TYPE_CHUNK = "chunk";
    private static final String CONV = "conv";
    private static final String DOC_ID = "doc_id";
    private static final String KEY = "key";
    private static final String EMBEDDER = "embedder";
    private static final String JSON = "json";
    private static final String TEXT = "text";
    private static final String INDEX = "idx";
    private static final String DIMS = "dims";

    public record StoredChunk(int index, String text, float[] vector) {
    }

    /** A chunk found by {@link #search}; {@code score} is the cosine similarity (-1..1). */
    public record ScoredChunk(String documentId, int index, String text, double score) {
    }

    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    private final Path path;
    private Directory directory;
    private IndexWriter writer;
    private SearcherManager searchers;
    private boolean persistent;

    /** @param path the index folder, or {@code null} for an in-memory index */
    public RagStore(Path path) {
        this.path = path;
    }

    public synchronized String location() {
        open();
        return path == null ? "(in memory)" : path + (persistent ? "" : " (could not open: in-memory only)");
    }

    private void open() {
        if (writer != null && writer.isOpen()) {
            return;
        }
        closeSearchers();
        if (directory == null && path != null) {
            try {
                Files.createDirectories(path);
                directory = FSDirectory.open(path);
                writer = new IndexWriter(directory, config());
                persistent = true;
            } catch (IOException | RuntimeException e) {
                LOG.error("Could not open the RAG index at {} (is another instance running?); documents will not be "
                        + "kept after exit", path, e);
                closeQuietly(directory);
                directory = null;
                writer = null;
            }
        }
        try {
            if (directory == null) {
                directory = new ByteBuffersDirectory();
                persistent = false;
            }
            if (writer == null || !writer.isOpen()) {
                writer = new IndexWriter(directory, config());
            }
            searchers = new SearcherManager(writer, null);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not open the RAG index", e);
        }
    }

    private static IndexWriterConfig config() {
        return new IndexWriterConfig(new StandardAnalyzer()).setOpenMode(IndexWriterConfig.OpenMode.CREATE_OR_APPEND)
                .setCodec(new Lucene912Codec() {
                    private final KnnVectorsFormat vectors = new HighDimensionVectorsFormat();

                    @Override
                    public KnnVectorsFormat getKnnVectorsFormatForField(String field) {
                        return vectors;
                    }
                });
    }

    /** The standard HNSW format with a higher dimension limit; files stay readable by the stock format. */
    private static final class HighDimensionVectorsFormat extends KnnVectorsFormat {
        private final KnnVectorsFormat delegate = new Lucene99HnswVectorsFormat();

        HighDimensionVectorsFormat() {
            super("Lucene99HnswVectorsFormat");
        }

        @Override
        public KnnVectorsWriter fieldsWriter(SegmentWriteState state) throws IOException {
            return delegate.fieldsWriter(state);
        }

        @Override
        public KnnVectorsReader fieldsReader(SegmentReadState state) throws IOException {
            return delegate.fieldsReader(state);
        }

        @Override
        public int getMaxDimensions(String fieldName) {
            return MAX_DIMENSIONS;
        }
    }

    static String vectorField(int dimensions) {
        return "vector_" + dimensions;
    }

    private static String key(String conversationId, String documentId) {
        return conversationId + '\u0000' + documentId;
    }

    private static Query all(Term... terms) {
        BooleanQuery.Builder b = new BooleanQuery.Builder();
        for (Term t : terms) {
            b.add(new TermQuery(t), Occur.FILTER);
        }
        return b.build();
    }

    private interface Read<T> {
        T apply(IndexSearcher searcher) throws IOException;
    }

    private <T> T read(Read<T> action) {
        open();
        try {
            IndexSearcher searcher = searchers.acquire();
            try {
                return action.apply(searcher);
            } finally {
                searchers.release(searcher);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read the RAG index", e);
        }
    }

    private static TopDocs everything(IndexSearcher searcher, Query query) throws IOException {
        return searcher.search(query, Math.max(1, searcher.count(query)));
    }

    /** Newest first. */
    public synchronized List<RagDocument> documents(String conversationId) {
        List<RagDocument> result = read(s -> {
            List<RagDocument> docs = new ArrayList<>();
            StoredFields fields = s.storedFields();
            for (ScoreDoc hit : everything(s, all(new Term(TYPE, TYPE_DOC), new Term(CONV, conversationId))).scoreDocs) {
                readDocument(fields.document(hit.doc).get(JSON)).ifPresent(docs::add);
            }
            return docs;
        });
        result.sort(Comparator.comparing((RagDocument d) -> d.createdAt,
                Comparator.nullsLast(Comparator.reverseOrder())));
        return result;
    }

    public synchronized Optional<RagDocument> document(String conversationId, String documentId) {
        return read(s -> {
            TopDocs top = s.search(all(new Term(TYPE, TYPE_DOC), new Term(KEY, key(conversationId, documentId))), 1);
            return top.scoreDocs.length == 0 ? Optional.<RagDocument>empty()
                    : readDocument(s.storedFields().document(top.scoreDocs[0].doc).get(JSON));
        });
    }

    private Optional<RagDocument> readDocument(String json) {
        try {
            return Optional.of(mapper.readValue(json, RagDocument.class));
        } catch (IOException | IllegalArgumentException e) {
            LOG.warn("Skipping unreadable RAG document record", e);
            return Optional.empty();
        }
    }

    /** The document's chunks in order, with their vectors. */
    public synchronized List<StoredChunk> chunks(String conversationId, String documentId) {
        List<StoredChunk> result = read(s -> {
            List<StoredChunk> out = new ArrayList<>();
            Query query = s.rewrite(all(new Term(TYPE, TYPE_CHUNK), new Term(KEY, key(conversationId, documentId))));
            Weight weight = s.createWeight(query, ScoreMode.COMPLETE_NO_SCORES, 1f);
            for (LeafReaderContext leaf : s.getIndexReader().leaves()) {
                Scorer scorer = weight.scorer(leaf);
                if (scorer == null) {
                    continue;
                }
                Bits live = leaf.reader().getLiveDocs();
                StoredFields fields = leaf.reader().storedFields();
                Map<String, FloatVectorValues> vectors = new HashMap<>();
                DocIdSetIterator it = scorer.iterator();
                for (int d = it.nextDoc(); d != DocIdSetIterator.NO_MORE_DOCS; d = it.nextDoc()) {
                    if (live != null && !live.get(d)) {
                        continue;
                    }
                    Document doc = fields.document(d);
                    int dims = doc.getField(DIMS).numericValue().intValue();
                    float[] vector = new float[dims];
                    String field = vectorField(dims);
                    if (!vectors.containsKey(field)) {
                        vectors.put(field, leaf.reader().getFloatVectorValues(field));
                    }
                    FloatVectorValues values = vectors.get(field);
                    // Docs come in increasing order, so one forward-only iterator per field is enough.
                    if (values != null && values.docID() < d) {
                        values.advance(d);
                    }
                    if (values != null && values.docID() == d) {
                        vector = values.vectorValue().clone();
                    }
                    out.add(new StoredChunk(doc.getField(INDEX).numericValue().intValue(), doc.get(TEXT), vector));
                }
            }
            return out;
        });
        result.sort(Comparator.comparingInt(StoredChunk::index));
        return result;
    }

    /** Stores (or replaces) a document and its chunks atomically. */
    public synchronized void put(RagDocument document, List<StoredChunk> documentChunks) throws IOException {
        open();
        String key = key(document.conversationId, document.id);
        List<Document> records = new ArrayList<>(documentChunks.size() + 1);
        Document meta = new Document();
        meta.add(new StringField(TYPE, TYPE_DOC, Field.Store.NO));
        meta.add(new StringField(CONV, document.conversationId, Field.Store.YES));
        meta.add(new StringField(DOC_ID, document.id, Field.Store.YES));
        meta.add(new StringField(KEY, key, Field.Store.NO));
        meta.add(new StoredField(JSON, mapper.writeValueAsString(document)));
        records.add(meta);
        for (StoredChunk c : documentChunks) {
            if (c.vector().length > MAX_DIMENSIONS) {
                throw new IOException("Embeddings with " + c.vector().length + " dimensions are not supported (max "
                        + MAX_DIMENSIONS + ")");
            }
            Document chunk = new Document();
            chunk.add(new StringField(TYPE, TYPE_CHUNK, Field.Store.NO));
            chunk.add(new StringField(CONV, document.conversationId, Field.Store.YES));
            chunk.add(new StringField(DOC_ID, document.id, Field.Store.YES));
            chunk.add(new StringField(KEY, key, Field.Store.NO));
            chunk.add(new StringField(EMBEDDER, String.valueOf(document.embedder), Field.Store.NO));
            chunk.add(new StoredField(INDEX, c.index()));
            chunk.add(new StoredField(DIMS, c.vector().length));
            chunk.add(new TextField(TEXT, c.text(), Field.Store.YES));
            if (norm(c.vector()) > 0) {
                // Zero vectors (e.g. text of only stop words) can't be compared by cosine: keep the text only.
                chunk.add(new KnnFloatVectorField(vectorField(c.vector().length), c.vector(),
                        VectorSimilarityFunction.COSINE));
            }
            records.add(chunk);
        }
        try {
            writer.updateDocuments(new Term(KEY, key), records);
            commit();
        } catch (IOException | RuntimeException e) {
            rollback();
            throw new IOException("Could not save document " + document.name + ": " + e.getMessage(), e);
        }
    }

    public synchronized boolean delete(String conversationId, String documentId) {
        boolean exists = document(conversationId, documentId).isPresent();
        deleteAll(new Term(KEY, key(conversationId, documentId)));
        return exists;
    }

    /** @return the number of documents removed */
    public synchronized int deleteConversation(String conversationId) {
        int count = read(s -> s.count(all(new Term(TYPE, TYPE_DOC), new Term(CONV, conversationId))));
        deleteAll(new Term(CONV, conversationId));
        return count;
    }

    private void deleteAll(Term term) {
        open();
        try {
            writer.deleteDocuments(term);
            commit();
        } catch (IOException e) {
            rollback();
            throw new UncheckedIOException("Could not delete from the RAG index", e);
        }
    }

    /** Ids of the conversations that have documents. */
    public synchronized Set<String> conversations() {
        return read(s -> {
            Set<String> ids = new LinkedHashSet<>();
            StoredFields fields = s.storedFields();
            for (ScoreDoc hit : everything(s, new TermQuery(new Term(TYPE, TYPE_DOC))).scoreDocs) {
                ids.add(fields.document(hit.doc).get(CONV));
            }
            return ids;
        });
    }

    /**
     * Approximate (HNSW) nearest-neighbour search over the conversation's chunks embedded by {@code embedderId};
     * small filtered sets are searched exactly. Best first.
     */
    public synchronized List<ScoredChunk> search(String conversationId, String embedderId, float[] query, int k) {
        if (k <= 0 || query.length == 0 || query.length > MAX_DIMENSIONS || norm(query) == 0) {
            return List.of();
        }
        Query filter = all(new Term(TYPE, TYPE_CHUNK), new Term(CONV, conversationId), new Term(EMBEDDER, embedderId));
        return read(s -> {
            int candidates = Math.max(MIN_CANDIDATES, k * 10);
            TopDocs top = s.search(new KnnFloatVectorQuery(vectorField(query.length), query, candidates, filter), k);
            List<ScoredChunk> hits = new ArrayList<>(top.scoreDocs.length);
            StoredFields fields = s.storedFields();
            for (ScoreDoc sd : top.scoreDocs) {
                Document doc = fields.document(sd.doc);
                // Lucene scores cosine as (1 + cos) / 2.
                hits.add(new ScoredChunk(doc.get(DOC_ID), doc.getField(INDEX).numericValue().intValue(),
                        doc.get(TEXT), 2.0 * sd.score - 1.0));
            }
            return hits;
        });
    }

    private static double norm(float[] v) {
        double sum = 0;
        for (float f : v) {
            sum += f * f;
        }
        return Math.sqrt(sum);
    }

    private void commit() throws IOException {
        writer.commit();
        searchers.maybeRefreshBlocking();
    }

    private void rollback() {
        try {
            writer.rollback(); // closes the writer; the next call reopens it at the last commit
        } catch (IOException | RuntimeException e) {
            LOG.warn("Rolling back the RAG index failed", e);
        }
    }

    private void closeSearchers() {
        if (searchers != null) {
            try {
                searchers.close();
            } catch (IOException e) {
                LOG.warn("Could not close RAG searchers", e);
            }
            searchers = null;
        }
    }

    private static void closeQuietly(AutoCloseable c) {
        if (c != null) {
            try {
                c.close();
            } catch (Exception ignored) {
                // best effort
            }
        }
    }

    @Override
    public synchronized void close() {
        closeSearchers();
        if (writer != null && writer.isOpen()) {
            try {
                writer.close();
            } catch (IOException e) {
                LOG.warn("Could not close the RAG index", e);
            }
        }
        writer = null;
        closeQuietly(directory);
        directory = null;
    }
}
