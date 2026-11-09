package net.talaatharb.harness.tools.builtin;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.Assumptions;

class FileDiscoveryToolTest {
    @TempDir Path temp;
    private final FileDiscoveryTool tool = new FileDiscoveryTool();
    private final ObjectMapper json = new ObjectMapper();

    private JsonNode run(Map<String, Object> args) throws Exception {
        var result = tool.execute(args);
        assertTrue(result.success(), result.output());
        return json.readTree(result.output());
    }

    @Test
    void placeholderControlsUseDefaults() throws Exception {
        Files.writeString(temp.resolve("note.txt"), "hello");
        var args = new java.util.HashMap<String, Object>();
        args.put("path", temp.toString());
        args.put("operation", "default");
        args.put("glob", null);
        args.put("maxResults", "default");
        args.put("maxDepth", "");
        args.put("includeHidden", "false");
        assertEquals(1, run(args).path("results").size());
    }

    @Test
    void listsMetadataAndFindsNamesWithinTheDepthLimit() throws Exception {
        Path nested = Files.createDirectories(temp.resolve("nested"));
        Files.writeString(nested.resolve("note.txt"), "hello");
        Files.writeString(temp.resolve("top.txt"), "top");
        Files.writeString(temp.resolve(".hidden.txt"), "hidden");
        var listed = run(Map.of("path", temp.toString()));
        assertEquals(2, listed.path("results").size());
        assertFalse(listed.toString().contains("note.txt"));
        assertTrue(listed.toString().contains("modified"));
        var found = run(Map.of("path", temp.toString(), "operation", "find", "glob", "*.txt"));
        assertEquals(2, found.path("results").size());
        assertFalse(found.toString().contains(".hidden.txt"));
        assertEquals(1, run(Map.of("path", temp.toString(), "operation", "find", "glob", "*.txt",
                "maxDepth", 1)).path("results").size());
        assertEquals(3, run(Map.of("path", temp.toString(), "operation", "find", "glob", "*.txt",
                "includeHidden", true)).path("results").size());
    }

    @Test
    void searchesLiteralUnicodeTextWithLineNumbersAndSkipsBinaryOrOversizedFiles() throws Exception {
        Files.writeString(temp.resolve("a.txt"), "first\nHello [world].\n\u0130 HELLO again");
        Files.write(temp.resolve("binary.txt"), new byte[] { 0, 'h', 'e', 'l', 'l', 'o' });
        Files.write(temp.resolve("invalid.txt"), new byte[] { (byte) 255 });
        Files.write(temp.resolve("large.txt"), new byte[FileDiscoveryTool.MAX_FILE_BYTES + 1]);
        var literal = run(Map.of("path", temp.toString(), "operation", "search", "query", "[world]."));
        assertEquals(1, literal.path("results").size());
        assertEquals(2, literal.path("results").get(0).path("line").asInt());
        assertEquals(3, literal.path("skippedFiles").asInt());
        var insensitive = run(Map.of("path", temp.toString(), "operation", "search", "query", "hello"));
        assertEquals(2, insensitive.path("results").size());
        assertEquals(0, run(Map.of("path", temp.toString(), "operation", "search", "query", "hello",
                "caseSensitive", true)).path("results").size());
    }

    @Test
    void reportsResultLimitAndRejectsBadArguments() throws Exception {
        Files.writeString(temp.resolve("a.txt"), "a\na");
        var result = run(Map.of("path", temp.toString(), "operation", "search", "query", "a", "maxResults", 1));
        assertEquals(1, result.path("results").size());
        assertTrue(result.path("incomplete").asBoolean());
        assertTrue(result.path("stopReason").asText().contains("Result limit"));
        for (var args : java.util.List.of(Map.<String, Object>of(), Map.<String, Object>of("path", temp.toString(),
                "operation", "search"), Map.<String, Object>of("path", temp.toString(), "maxDepth", -1),
                Map.<String, Object>of("path", temp.toString(), "maxResults", 1.5),
                Map.<String, Object>of("path", temp.toString(), "glob", "["))) {
            assertFalse(tool.execute(args).success());
        }
    }

    @Test
    void capsOutputWithoutProducingInvalidJson() throws Exception {
        Files.writeString(temp.resolve("many.txt"), ("match " + "x".repeat(294) + "\n").repeat(200));
        var result = run(Map.of("path", temp.toString(), "operation", "search", "query", "match", "maxResults", 500));
        assertTrue(result.path("results").size() > 0);
        assertTrue(result.path("results").size() < 200);
        assertTrue(result.path("incomplete").asBoolean());
        assertTrue(result.path("stopReason").asText().contains("Output limit"));
    }

    @Test
    void doesNotTraverseSymbolicLinks() throws Exception {
        Path outside = Files.createDirectory(temp.resolve("outside"));
        Files.writeString(outside.resolve("secret.txt"), "not inside");
        Path root = Files.createDirectory(temp.resolve("root"));
        try {
            Files.createSymbolicLink(root.resolve("linked"), outside);
        } catch (java.io.IOException | UnsupportedOperationException e) {
            Assumptions.abort("Symbolic link creation is unavailable: " + e.getClass().getSimpleName());
        }
        assertEquals(0, run(Map.of("path", root.toString(), "operation", "find")).path("results").size());
        assertFalse(tool.execute(Map.of("path", root.resolve("linked").toString())).success());
    }
}
