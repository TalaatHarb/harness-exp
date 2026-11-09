package net.talaatharb.harness.tools.builtin;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import net.talaatharb.harness.agent.ConversationScope;
import net.talaatharb.harness.history.ArtifactStore;
import net.talaatharb.harness.tools.RiskLevel;
import net.talaatharb.harness.tools.ToolResult;

class WriteBinaryFileToolTest {
    @TempDir Path temp;

    private static ArtifactStore.Artifact artifact(ToolResult result) throws IOException {
        assertTrue(result.success(), result.output());
        return new ObjectMapper().readValue(result.output().substring(ArtifactStore.ARTIFACT_PREFIX.length()),
                ArtifactStore.Artifact.class);
    }

    @Test
    void savesBase64ImageAndImportsAudioWithoutChangingBytes() throws Exception {
        ArtifactStore store = new ArtifactStore(temp.resolve("history.db"));
        WriteBinaryFileTool tool = new WriteBinaryFileTool(store);
        assertEquals(RiskLevel.SENSITIVE, tool.risk());
        String png = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+j4WQAAAAASUVORK5CYII=";
        byte[] audio = net.talaatharb.harness.speech.WavIO.toWavBytes(
                new net.talaatharb.harness.speech.AudioClip(new float[] { 0, 0.5f, -0.5f }, 16000));
        Path source = temp.resolve("source.wav");
        Files.write(source, audio);
        String previous = ConversationScope.enter("conversation");
        try {
            var image = artifact(tool.execute(Map.of("path", "image.png", "base64", png)));
            assertArrayEquals(Base64.getDecoder().decode(png), Files.readAllBytes(store.file("conversation", image)));
            var sound = artifact(tool.execute(Map.of("path", "sound.wav", "sourcePath", source.toString())));
            assertArrayEquals(audio, Files.readAllBytes(store.file("conversation", sound)));
            Path saved = temp.resolve("saved.wav");
            store.saveAs("conversation", sound, saved);
            store.deleteConversation("conversation");
            assertArrayEquals(audio, Files.readAllBytes(source));
            assertArrayEquals(audio, Files.readAllBytes(saved));
            assertThrows(IOException.class, () -> store.file("conversation", sound));
        } finally {
            ConversationScope.restore(previous);
        }
    }

    @Test
    void ordinaryBinaryWritesReplaceFilesAndCreateParents() throws Exception {
        WriteBinaryFileTool tool = new WriteBinaryFileTool(new ArtifactStore(temp.resolve("history.db")));
        Path file = temp.resolve("nested/image.bin");
        byte[] bytes = { 0, (byte) 255, (byte) 128, 13, 10 };
        assertTrue(tool.execute(Map.of("path", file.toString(), "artifact", false,
                "base64", Base64.getEncoder().encodeToString(bytes))).success());
        assertArrayEquals(bytes, Files.readAllBytes(file));
        assertTrue(tool.execute(Map.of("path", file.toString(), "artifact", false, "base64", "AQ==")).success());
        assertArrayEquals(new byte[] { 1 }, Files.readAllBytes(file));
        assertFalse(tool.execute(Map.of("path", temp.toString(), "artifact", false, "base64", "AQ==")).success());
    }

    @Test
    void rejectsInvalidInputsAndOversizeDataBeforeWriting() throws Exception {
        WriteBinaryFileTool tool = new WriteBinaryFileTool(new ArtifactStore(temp.resolve("history.db")));
        assertFalse(tool.execute(null).success());
        assertFalse(tool.execute(Map.of("path", "test.bin", "base64", "AA==")).success());
        String previous = ConversationScope.enter("conversation");
        try {
            assertFalse(tool.execute(Map.of("path", "test.bin")).success());
            assertFalse(tool.execute(Map.of("path", "test.bin", "base64", 42)).success());
            assertFalse(tool.execute(Map.of("path", "test.bin", "base64", "AA==", "sourcePath", "x")).success());
            assertFalse(tool.execute(Map.of("path", "test.bin", "base64", "!", "artifact", true)).success());
            assertFalse(tool.execute(Map.of("path", "../escape.bin", "base64", "AA==")).success());
            assertFalse(tool.execute(Map.of("path", "test.bin", "base64", "AA==", "artifact", "maybe")).success());
            assertFalse(tool.execute(Map.of("path", "test.bin", "base64", "AA==", "append", true)).success());
            assertThrows(IOException.class, () -> tool.execute(Map.of("path", "test.bin",
                    "sourcePath", temp.resolve("missing.bin").toString())));
            assertThrows(IOException.class, () -> tool.execute(Map.of("path", "test.bin", "sourcePath", temp.toString())));
            String huge = "A".repeat(4 * ((ArtifactStore.MAX_BINARY_BYTES + 2) / 3) + 4);
            assertFalse(tool.execute(Map.of("path", "test.bin", "base64", huge)).success());
            Path oversized = temp.resolve("oversize.bin");
            try (var file = new java.io.RandomAccessFile(oversized.toFile(), "rw")) {
                file.setLength(ArtifactStore.MAX_BINARY_BYTES + 1L);
            }
            assertThrows(IOException.class, () -> tool.execute(Map.of("path", "test.bin",
                    "sourcePath", oversized.toString())));
            assertFalse(Files.exists(temp.resolve("history.db.artifacts")));
        } finally {
            ConversationScope.restore(previous);
        }

    }
}
