package net.talaatharb.harness.rag;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.sun.net.httpserver.HttpServer;

import net.talaatharb.harness.agent.ConversationScope;
import net.talaatharb.harness.config.HarnessSettings;
import net.talaatharb.harness.context.ContextAssembly;
import net.talaatharb.harness.tools.ToolResult;

class RagTest {
    private static final String CAT = "Cats are small domesticated carnivores. A cat sleeps most of the day and "
            + "purrs when content. Kittens are young cats.";
    private static final String ROCKET = "Rockets burn liquid oxygen and kerosene. The first stage separates "
            + "after launch and the second stage reaches orbit.";

    @TempDir
    Path dir;
    private final List<AutoCloseable> open = new ArrayList<>();
    private final HarnessSettings settings = new HarnessSettings();
    private Function<HarnessSettings, Embedder> embedders = s -> new HashingEmbedder();

    @AfterEach
    void close() throws Exception {
        for (AutoCloseable c : open) {
            c.close();
        }
    }

    private RagService service() {
        RagStore store = new RagStore(dir.resolve("rag-index"));
        open.add(store);
        return new RagService(() -> settings, store, s -> embedders.apply(s));
    }

    @Test
    void chunkerSplitsWithOverlapAndRespectsSize() {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < 200; i++) {
            text.append("Sentence number ").append(i).append(" talks about topic ").append(i % 7).append(". ");
            if (i % 10 == 9) {
                text.append("\n\n");
            }
        }
        List<String> chunks = TextChunker.chunk(text.toString(), 400, 80);
        assertTrue(chunks.size() > 5);
        for (String c : chunks) {
            assertTrue(c.length() <= 400 + 80, "chunk too long: " + c.length());
            assertFalse(c.isBlank());
        }
        // consecutive chunks share text (overlap)
        String tail = chunks.get(0).substring(chunks.get(0).length() - 20);
        assertTrue(chunks.get(1).contains(tail.strip().split(" ")[tail.strip().split(" ").length - 1]));
        assertTrue(TextChunker.chunk("   ", 400, 80).isEmpty());
        assertEquals(List.of("short text"), TextChunker.chunk("short text", 400, 80));
        // a single huge word is hard-split
        assertTrue(TextChunker.chunk("x".repeat(1000), 300, 0).size() >= 3);
    }

    @Test
    void hashingEmbedderRanksRelatedTextHigher() {
        HashingEmbedder e = new HashingEmbedder();
        List<float[]> v = e.embed(List.of("how long do cats sleep", CAT, ROCKET));
        assertEquals(1024, v.get(0).length);
        assertTrue(Embedder.cosine(v.get(0), v.get(1)) > Embedder.cosine(v.get(0), v.get(2)));
        assertEquals(1.0, Embedder.cosine(v.get(1), v.get(1)), 1e-5);
    }

    @Test
    void storeRoundTripDeleteAndConversations() throws Exception {
        RagStore store = new RagStore(dir.resolve("store"));
        RagDocument doc = new RagDocument();
        doc.id = "d1";
        doc.conversationId = "c1";
        doc.name = "notes";
        doc.chunks = 2;
        doc.embedder = HashingEmbedder.ID;
        store.put(doc, List.of(new RagStore.StoredChunk(0, "first", new float[] {1, 0}),
                new RagStore.StoredChunk(1, "second", new float[] {0, 1})));
        RagDocument other = doc.copy();
        other.conversationId = "c2";
        store.put(other, List.of(new RagStore.StoredChunk(0, "x", new float[] {1, 1})));
        store.close();

        store = new RagStore(dir.resolve("store"));
        open.add(store);
        assertEquals(Set.of("c1", "c2"), store.conversations());
        assertEquals("notes", store.documents("c1").get(0).name);
        List<RagStore.StoredChunk> chunks = store.chunks("c1", "d1");
        assertEquals(2, chunks.size());
        assertEquals("second", chunks.get(1).text());
        assertEquals(1f, chunks.get(1).vector()[1]);
        assertTrue(store.delete("c1", "d1"));
        assertTrue(store.chunks("c1", "d1").isEmpty());
        assertEquals(1, store.documents("c2").size());
        assertEquals(1, store.deleteConversation("c2"));
        assertTrue(store.conversations().isEmpty());
    }

    @Test
    void storeSearchesLargeVectorsAndSkipsZeroVectors() throws Exception {
        RagStore store = new RagStore(null);
        open.add(store);
        int dims = 1536; // above Lucene's default 1024 limit
        float[] a = new float[dims];
        float[] b = new float[dims];
        a[0] = 1;
        b[1] = 1;
        RagDocument doc = new RagDocument();
        doc.id = "big";
        doc.conversationId = "c";
        doc.name = "big";
        doc.embedder = "e1";
        store.put(doc, List.of(new RagStore.StoredChunk(0, "alpha", a), new RagStore.StoredChunk(1, "beta", b),
                new RagStore.StoredChunk(2, "the", new float[dims])));
        List<RagStore.ScoredChunk> hits = store.search("c", "e1", a, 5);
        assertEquals(2, hits.size());
        assertEquals("alpha", hits.get(0).text());
        assertEquals(1.0, hits.get(0).score(), 1e-5);
        assertEquals(0.0, hits.get(1).score(), 1e-5);
        assertTrue(store.search("c", "other-embedder", a, 5).isEmpty());
        assertTrue(store.search("other", "e1", a, 5).isEmpty());
        assertTrue(store.search("c", "e1", new float[dims], 5).isEmpty());
        assertEquals(3, store.chunks("c", "big").size());
        assertEquals(1f, store.chunks("c", "big").get(1).vector()[1]);
        // replacing a document drops its old chunks
        store.put(doc, List.of(new RagStore.StoredChunk(0, "gamma", b)));
        assertEquals(List.of("gamma"), store.chunks("c", "big").stream().map(RagStore.StoredChunk::text).toList());
        assertEquals(1, store.documents("c").size());
    }

    @Test
    void storeSearchMatchesExactRanking() throws Exception {
        RagStore store = new RagStore(null);
        open.add(store);
        java.util.Random random = new java.util.Random(7);
        List<RagStore.StoredChunk> chunks = new ArrayList<>();
        for (int i = 0; i < 600; i++) {
            float[] v = new float[64];
            for (int j = 0; j < v.length; j++) {
                v[j] = (float) random.nextGaussian();
            }
            chunks.add(new RagStore.StoredChunk(i, "chunk " + i, v));
        }
        RagDocument doc = new RagDocument();
        doc.id = "d";
        doc.conversationId = "c";
        doc.name = "random";
        doc.embedder = "e";
        store.put(doc, chunks);
        for (int q = 0; q < 5; q++) {
            float[] query = chunks.get(random.nextInt(chunks.size())).vector().clone();
            query[0] += 0.5f;
            List<Integer> exact = chunks.stream()
                    .sorted(java.util.Comparator.comparingDouble(
                            (RagStore.StoredChunk c) -> -Embedder.cosine(query, c.vector())))
                    .limit(5).map(RagStore.StoredChunk::index).toList();
            List<Integer> found = store.search("c", "e", query, 5).stream().map(RagStore.ScoredChunk::index).toList();
            assertEquals(exact, found);
        }
    }

    @Test
    void ingestSearchCopyAndPrune() throws Exception {
        RagService rag = service();
        RagDocument cats = rag.ingestText("c1", "cats.md", "test", CAT, null);
        rag.ingestText("c1", "rockets.md", "test", ROCKET, null);
        assertEquals(2, rag.documents("c1").size());
        assertTrue(rag.documents("c2").isEmpty(), "documents are per conversation");

        RagService.SearchResult result = rag.search("c1", "when do kittens purr", 5, 0.0);
        assertEquals("cats.md", result.hits().get(0).document().name);
        assertTrue(rag.search("c2", "kittens", 5, 0.0).hits().isEmpty());

        List<RagDocument> copied = rag.copy("c1", List.of(cats.id), "c2");
        assertEquals(1, copied.size());
        assertEquals("cats.md", rag.search("c2", "kittens", 5, 0.0).hits().get(0).document().name);
        // copying again into the same conversation gets a fresh id
        RagDocument again = rag.copy("c1", List.of(cats.id), "c2").get(0);
        assertFalse(again.id.equals(cats.id));
        assertEquals(2, rag.documents("c2").size());

        assertEquals(2, rag.prune(Set.of("c2")));
        assertTrue(rag.documents("c1").isEmpty());
        assertEquals(2, rag.deleteConversation("c2"));
    }

    @Test
    void ingestFileAndFolder() throws Exception {
        Files.writeString(dir.resolve("a.md"), CAT);
        Files.createDirectories(dir.resolve("sub"));
        Files.writeString(dir.resolve("sub").resolve("b.txt"), ROCKET);
        Files.write(dir.resolve("image.png"), new byte[] {1, 2, 3});
        for (String ext : List.of("xml", "html", "java", "cs", "py")) {
            Files.writeString(dir.resolve("sub").resolve("code." + ext), "content " + ext);
        }
        List<Path> files = RagService.indexableFiles(dir);
        assertEquals(7, files.size(), files.toString());

        RagService rag = service();
        RagDocument doc = rag.ingestFile("c1", dir.resolve("a.md"), "Cat facts", null);
        assertEquals("Cat facts", doc.name);
        assertEquals("a.md", rag.ingestFile("c1", dir.resolve("a.md"), null).name);
        assertThrows(IllegalArgumentException.class, () -> rag.ingestText("c1", "empty", "x", "   ", null));
        assertThrows(IllegalStateException.class, () -> rag.ingestText(null, "n", "x", CAT, null));
    }

    @Test
    void otherEmbedderDocumentsAreReportedAndReindexed() throws Exception {
        RagService rag = service();
        rag.ingestText("c1", "cats.md", "test", CAT, null);
        embedders = s -> new PrefixedEmbedder();
        RagService.SearchResult result = rag.search("c1", "kittens", 5, 0.0);
        assertTrue(result.hits().isEmpty());
        assertEquals(1, result.otherEmbedder().size());

        assertEquals(1, rag.reindex("c1", null));
        assertEquals("test:other", rag.documents("c1").get(0).embedder);
        assertEquals(1, rag.search("c1", "kittens", 5, 0.0).hits().size());
        assertEquals(0, rag.reindex("c1", null));
    }

    /** Same vectors as the built-in embedder under a different id. */
    private static final class PrefixedEmbedder implements Embedder {
        @Override
        public String id() {
            return "test:other";
        }

        @Override
        public List<float[]> embed(List<String> texts) {
            return new HashingEmbedder().embed(texts);
        }
    }

    @Test
    void ollamaEmbedderCallsApiAndExplainsErrors() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicReference<String> body = new AtomicReference<>();
        server.createContext("/api/embed", ex -> {
            body.set(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            boolean bad = body.get().contains("\"chat-model\"");
            byte[] out = (bad ? "{\"error\":\"this model does not support embeddings\"}"
                    : "{\"embeddings\":[[0.1,0.2,0.3],[0.3,0.2,0.1]]}").getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(bad ? 400 : 200, out.length);
            ex.getResponseBody().write(out);
            ex.close();
        });
        server.start();
        try {
            String base = "http://127.0.0.1:" + server.getAddress().getPort();
            OllamaEmbedder ok = new OllamaEmbedder(base, "nomic-embed-text", null, Duration.ofSeconds(5));
            assertEquals("ollama:nomic-embed-text", ok.id());
            List<float[]> v = ok.embed(List.of("a", "b"));
            assertEquals(2, v.size());
            assertEquals(0.3f, v.get(1)[0], 1e-6);
            assertTrue(body.get().contains("\"model\":\"nomic-embed-text\""), body.get());

            OllamaEmbedder bad = new OllamaEmbedder(base, "chat-model", null, Duration.ofSeconds(5));
            IOException e = assertThrows(IOException.class, () -> bad.embed(List.of("a")));
            assertTrue(e.getMessage().toLowerCase().contains("embed"), e.getMessage());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void embedderSelectionFollowsSettings() {
        HarnessSettings s = new HarnessSettings();
        assertInstanceOf(OllamaEmbedder.class, RagService.embedderFor(s));
        s.rag.embedder = RagSettings.EmbedderType.BUILT_IN;
        assertInstanceOf(HashingEmbedder.class, RagService.embedderFor(s));
        s.rag.embedder = RagSettings.EmbedderType.OLLAMA;
        s.rag.embeddingBaseUrl = "http://example:1234";
        assertEquals("http://example:1234", ((OllamaEmbedder) RagService.embedderFor(s)).baseUrl());
    }

    @Test
    void toolsUseTheCurrentConversation() throws Exception {
        RagService rag = service();
        RagIngestTool add = new RagIngestTool(rag);
        RagSearchTool search = new RagSearchTool(rag);
        assertFalse(add.execute(Map.of("name", "cats", "text", CAT)).success(), "no conversation open");

        String previous = ConversationScope.enter("c1");
        try {
            assertTrue(search.execute(Map.of("query", "cats")).output().contains("rag_add"));
            ToolResult added = add.execute(Map.of("name", "cats", "text", CAT));
            assertTrue(added.success(), added.output());
            Files.writeString(dir.resolve("r.md"), ROCKET);
            assertTrue(add.execute(Map.of("path", dir.resolve("r.md").toString())).success());

            ToolResult found = search.execute(Map.of("query", "how much do cats sleep"));
            assertTrue(found.success());
            assertTrue(found.output().contains("cats"), found.output());
            ToolResult listing = search.execute(Map.of());
            assertTrue(listing.output().contains("r.md"), listing.output());
        } finally {
            ConversationScope.restore(previous);
        }
        assertEquals(2, rag.documents("c1").size());
        assertEquals(null, ConversationScope.current());
    }

    @Test
    void contributorAddsRelevantPassagesOnlyWhenDocumentsExist() throws Exception {
        RagService rag = service();
        RagContextContributor contributor = new RagContextContributor(rag);
        ContextAssembly empty = new ContextAssembly(settings, List.of(), "do cats purr?", "c1");
        contributor.contribute(empty);
        assertTrue(empty.systemSections().isEmpty());

        rag.ingestText("c1", "cats.md", "test", CAT, null);
        rag.ingestText("c1", "rockets.md", "test", ROCKET, null);
        ContextAssembly a = new ContextAssembly(settings, List.of(), "do cats purr?", "c1");
        contributor.contribute(a);
        assertEquals(1, a.systemSections().size());
        String section = a.systemSections().get(0);
        assertTrue(section.startsWith("## Documents"), section);
        assertTrue(section.contains("purrs"), section);

        settings.rag.autoContext = false;
        ContextAssembly off = new ContextAssembly(settings, List.of(), "do cats purr?", "c1");
        contributor.contribute(off);
        assertTrue(off.systemSections().isEmpty());
    }

    @Test
    void contributorReportsRetrievalFailures() throws Exception {
        RagService rag = service();
        rag.ingestText("c1", "cats.md", "test", CAT, null);
        embedders = s -> new Embedder() {
            @Override
            public String id() {
                return HashingEmbedder.ID;
            }

            @Override
            public List<float[]> embed(List<String> texts) throws IOException {
                throw new IOException("model not found");
            }
        };
        ContextAssembly a = new ContextAssembly(settings, List.of(), "cats", "c1");
        new RagContextContributor(rag).contribute(a);
        assertTrue(a.systemSections().get(0).contains("model not found"));
    }
}
