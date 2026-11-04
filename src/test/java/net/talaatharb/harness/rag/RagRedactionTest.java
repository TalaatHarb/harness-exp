package net.talaatharb.harness.rag;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import net.talaatharb.harness.config.HarnessSettings;

class RagRedactionTest {
    @TempDir Path temp;

    @Test void sanitizesBeforeChunkingEmbeddingAndQueryEgress() throws Exception {
        HarnessSettings settings = new HarnessSettings();
        settings.rag.chunkSize = 100;
        List<String> sent = new ArrayList<>();
        Embedder embedder = new Embedder() {
            public String id() { return "test"; }
            public List<float[]> embed(List<String> texts) {
                sent.addAll(texts);
                return texts.stream().map(t -> new float[]{1, 0}).toList();
            }
        };
        String secret = "sk-test1234567890abcdef1234567890abcdef";
        try (RagService rag = new RagService(() -> settings, new RagStore(temp.resolve("index")), s -> embedder)) {
            RagDocument doc = rag.ingestText("chat", secret, secret, "Document key: " + secret, null);
            rag.search("chat", secret, 1, 0);
            assertFalse(doc.name.contains(secret));
            assertFalse(doc.source.contains(secret));
            assertFalse(sent.isEmpty());
            assertTrue(sent.stream().noneMatch(t -> t.contains(secret)));
            assertTrue(rag.store().chunks("chat", doc.id).stream().noneMatch(c -> c.text().contains(secret)));
        }
    }
}
