package net.talaatharb.harness.history;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import net.talaatharb.harness.agent.ConversationScope;
import net.talaatharb.harness.tools.builtin.WriteFileTool;

class ArtifactStoreTest {
    @TempDir Path temp;

    @Test
    void writesDownloadableStoryAndKeepsItAcrossRestarts() throws Exception {
        Path db = temp.resolve("history.db");
        ArtifactStore store = new ArtifactStore(db);
        WriteFileTool tool = new WriteFileTool(store);
        assertFalse(tool.execute(Map.of("artifact", true, "path", "story.md", "content", "Hello")).success());
        String previous = ConversationScope.enter("conversation");
        try {
            var result = tool.execute(Map.of("artifact", true, "path", "story.md", "content", "# A story\nHéllo"));
            assertTrue(result.success(), result.output());
            var artifact = new ObjectMapper().readValue(result.output().substring(WriteFileTool.ARTIFACT_PREFIX.length()),
                    ArtifactStore.Artifact.class);
            assertFalse(Files.exists(temp.resolve("story.md")));
            ArtifactStore restarted = new ArtifactStore(db);
            assertEquals("# A story\nHéllo", Files.readString(restarted.file("conversation", artifact)));
            assertThrows(IOException.class, () -> restarted.file("other", artifact));
            Path saved = temp.resolve("saved.md");
            restarted.saveAs("conversation", artifact, saved);
            restarted.deleteConversation("conversation");
            assertEquals("# A story\nHéllo", Files.readString(saved));
            assertThrows(IOException.class, () -> restarted.file("conversation", artifact));
            assertThrows(IOException.class, () -> restarted.write("conversation", "late.txt", "late"));
        } finally {
            ConversationScope.restore(previous);
        }
    }

    @Test
    void findsNewestArtifactByPlainNameWithinItsConversationOnly() throws Exception {
        ArtifactStore store = new ArtifactStore(temp.resolve("history.db"));
        var older = store.writeBinary("conversation", "image.png", new byte[] { 1 });
        var newer = store.writeBinary("conversation", "image.png", new byte[] { 2 });
        Files.setLastModifiedTime(store.file("conversation", older), java.nio.file.attribute.FileTime.fromMillis(1000));
        assertEquals(store.file("conversation", newer), store.findByName("conversation", "image.png").orElseThrow());
        assertTrue(store.findByName("other", "image.png").isEmpty());
        assertTrue(store.findByName("conversation", "../image.png").isEmpty());
        assertTrue(store.findByName("conversation", "missing.png").isEmpty());
    }

    @Test
    void rejectsPathsAndArtifactAppendWithoutWritingOutsideStorage() throws Exception {
        ArtifactStore store = new ArtifactStore(temp.resolve("history.db"));
        for (String name : new String[] { "../escape.txt", "sub/story.txt", "sub\\story.txt", ".", "C:\\story.txt" }) {
            assertThrows(IllegalArgumentException.class, () -> store.write("conversation", name, "text"));
        }
        WriteFileTool tool = new WriteFileTool(store);
        String previous = ConversationScope.enter("conversation");
        try {
            assertFalse(tool.execute(Map.of("artifact", true, "path", "../bad.txt", "content", "x")).success());
            assertFalse(tool.execute(Map.of("artifact", true, "path", "story.txt", "content", "x", "append", true)).success());
            assertFalse(tool.execute(Map.of("artifact", "maybe", "path", "story.txt", "content", "x")).success());
        } finally {
            ConversationScope.restore(previous);
        }
    }

    @Test
    void deletingHistoryRemovesOnlyItsOwnedArtifacts() throws Exception {
        Path db = temp.resolve("history.db");
        var first = ConversationRecord.create();
        var second = ConversationRecord.create();
        ArtifactStore.Artifact firstFile;
        ArtifactStore.Artifact secondFile;
        try (ConversationStore history = new ConversationStore(db)) {
            history.save(first);
            history.save(second);
            firstFile = history.artifacts().write(first.id, "story.txt", "first");
            secondFile = history.artifacts().write(second.id, "story.txt", "second");
        }
        try (ConversationStore history = new ConversationStore(db)) {
            Path owned = history.artifacts().file(first.id, firstFile);
            Path saved = temp.resolve("my-story.txt");
            history.artifacts().saveAs(first.id, firstFile, saved);
            assertTrue(history.delete(first.id));
            assertThrows(IllegalStateException.class, () -> history.save(first));
            assertFalse(history.load(first.id).isPresent());
            assertFalse(Files.exists(owned));
            assertEquals("first", Files.readString(saved));
            assertEquals("second", Files.readString(history.artifacts().file(second.id, secondFile)));
        }
    }
}
