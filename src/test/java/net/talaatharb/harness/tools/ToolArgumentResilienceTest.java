package net.talaatharb.harness.tools;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.google.inject.Guice;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import net.talaatharb.harness.config.AppModule;
import net.talaatharb.harness.config.HarnessSettings;
import net.talaatharb.harness.history.ArtifactStore;
import net.talaatharb.harness.memory.MemoryService;
import net.talaatharb.harness.rag.RagService;
import net.talaatharb.harness.tools.builtin.*;

class ToolArgumentResilienceTest {
    @TempDir Path temp;

    @Test
    void allRegisteredBuiltinsHandleMissingAndExplicitNullArgumentsWithoutThrowing() throws Exception {
        var injector = Guice.createInjector(new AppModule(temp.resolve("settings.json")));
        var tools = injector.getInstance(ToolRegistry.class).all(new HarnessSettings());
        try {
            for (Tool tool : tools) {
                for (Map<String, Object> args : java.util.Arrays.<Map<String, Object>>asList(null, Map.of(),
                        nullFields())) {
                    ToolResult result = assertDoesNotThrow(() -> tool.execute(args), tool.name());
                    assertNotNull(result, tool.name());
                    if (!List.of("current_time", "recall").contains(tool.name())) {
                        assertFalse(result.success(), tool.name() + " must not act without required input");
                    }
                }
            }
        } finally {
            injector.getInstance(MemoryService.class).close();
            injector.getInstance(RagService.class).close();
        }
    }

    private static Map<String, Object> nullFields() {
        Map<String, Object> fields = new HashMap<>();
        for (String key : List.of("path", "url", "query", "text", "content", "command", "code", "question",
                "expression", "name", "sourceCode", "method", "body", "headers", "facts", "sourcePath", "base64")) {
            fields.put(key, null);
        }
        return fields;
    }

    @Test
    void optionalControlsAcceptPlaceholdersAndStringScalarsButRejectInvalidValues() {
        for (Object value : java.util.Arrays.asList(null, "", " ", "default", " DEFAULT ", "null")) {
            Map<String, Object> args = new HashMap<>();
            args.put("option", value);
            assertEquals(5, ToolArguments.integer(args, "option", 5, 10));
            assertTrue(ToolArguments.bool(args, "option", true));
            assertEquals("fallback", ToolArguments.string(args, "option", "fallback"));
        }
        assertEquals(5, ToolArguments.integer(Map.of("option", "5"), "option", 1, 10));
        assertFalse(ToolArguments.bool(Map.of("option", " false "), "option", true));
        for (Object invalid : List.of("oops", "NaN", "Infinity", 1.5, Map.of())) {
            assertThrows(IllegalArgumentException.class,
                    () -> ToolArguments.integer(Map.of("option", invalid), "option", 1, 10));
        }
        assertThrows(IllegalArgumentException.class, () -> ToolArguments.bool(Map.of("option", "maybe"), "option", false));
    }

    @Test
    void fileWritesPreserveLiteralContentAndIgnoreUnusedBinarySource() throws Exception {
        WriteFileTool text = new WriteFileTool();
        Path target = temp.resolve("text.txt");
        for (String content : List.of("", "null", "default", " ")) {
            Map<String, Object> args = new HashMap<>(Map.of("path", target.toString(), "content", content,
                    "append", "default"));
            args.put("artifact", null);
            assertTrue(text.execute(args).success());
            assertEquals(content, Files.readString(target));
        }
        assertFalse(text.execute(Map.of("path", target.toString(), "content", "overwrite", "append", "maybe")).success());
        assertEquals(" ", Files.readString(target));
        assertFalse(new ReadFileTool().execute(Map.of("path", "\0")).success());

        WriteBinaryFileTool binary = new WriteBinaryFileTool(new ArtifactStore(temp.resolve("history.db")));
        Path destination = temp.resolve("binary.bin");
        Map<String, Object> args = new HashMap<>(Map.of("path", destination.toString(), "base64", "AQ==",
                "sourcePath", "default", "artifact", "false", "append", "default"));
        assertTrue(binary.execute(args).success());
        assertArrayEquals(new byte[] { 1 }, Files.readAllBytes(destination));
        args.put("sourcePath", null);
        assertTrue(binary.execute(args).success());
        args.put("base64", "");
        assertFalse(binary.execute(args).success());
    }

    @Test
    void decisionToolIgnoresPlaceholderControlsWithoutChangingQuestion() throws Exception {
        var tool = new AskUserForDecisionTool(request -> {
            assertEquals("default", request.question());
            assertTrue(request.options().isEmpty());
            assertEquals("Your answer", request.inputLabel());
            return java.util.concurrent.CompletableFuture.completedFuture(
                    new net.talaatharb.harness.interaction.DecisionResponse(null, "response"));
        });
        assertTrue(tool.execute(Map.of("question", "default", "options", "default", "inputLabel", "null")).success());
    }
}
