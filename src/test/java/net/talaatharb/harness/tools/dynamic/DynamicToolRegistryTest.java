package net.talaatharb.harness.tools.dynamic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import net.talaatharb.harness.tools.RiskLevel;
import net.talaatharb.harness.tools.Tool;
import net.talaatharb.harness.tools.ToolResult;

class DynamicToolRegistryTest {
    static final String WORD_COUNT = """
            import java.util.Map;
            import net.talaatharb.harness.tools.dynamic.DynamicTool;

            public class WordCount implements DynamicTool {
                public String execute(Map<String, Object> arguments) {
                    String text = String.valueOf(arguments.getOrDefault("text", ""));
                    return String.valueOf(text.isBlank() ? 0 : text.trim().split("\\\\s+").length);
                }
            }
            """;

    @TempDir
    Path dir;

    private static DynamicToolSpec spec(String name, String source) {
        return new DynamicToolSpec(name, "test tool", "{\"text\": \"string\"}", source);
    }

    @Test
    void createsCompilesExecutesAndPersists() throws Exception {
        DynamicToolRegistry registry = new DynamicToolRegistry(dir);
        Tool tool = registry.create(spec("word_count", WORD_COUNT), Set.of());

        assertEquals(RiskLevel.SENSITIVE, tool.risk());
        assertEquals("dynamic", tool.source());
        assertEquals(new ToolResult(true, "3"), tool.execute(Map.of("text", "one two  three")));
        assertTrue(Files.isRegularFile(dir.resolve("word_count.json")));
        assertEquals(1, registry.tools().size());
    }

    @Test
    void reloadsStoredToolsOnStartup() throws Exception {
        new DynamicToolRegistry(dir).create(spec("word_count", WORD_COUNT), Set.of());
        Files.writeString(dir.resolve("broken.json"), "{not json");

        DynamicToolRegistry restarted = new DynamicToolRegistry(dir);

        assertEquals(1, restarted.tools().size());
        assertEquals("2", restarted.tools().get(0).execute(Map.of("text", "a b")).output());
        assertTrue(restarted.spec("word_count").orElseThrow().createdAt != null);
    }

    @Test
    void compileErrorsAreReportedAndNothingIsStored() {
        DynamicToolRegistry registry = new DynamicToolRegistry(dir);
        DynamicToolException e = assertThrows(DynamicToolException.class, () -> registry.create(
                spec("bad", "public class Bad implements net.talaatharb.harness.tools.dynamic.DynamicTool {"
                        + " public String execute(java.util.Map<String,Object> a) { return 1; } }"),
                Set.of()));

        assertTrue(e.getMessage().contains("line 1"), e.getMessage());
        assertFalse(Files.exists(dir.resolve("bad.json")));
        assertTrue(registry.tools().isEmpty());
    }

    @Test
    void rejectsInvalidDefinitions() {
        DynamicToolRegistry registry = new DynamicToolRegistry(dir);
        assertThrows(DynamicToolException.class, () -> registry.create(spec("Bad-Name", WORD_COUNT), Set.of()));
        assertThrows(DynamicToolException.class,
                () -> registry.create(spec("read_file", WORD_COUNT), Set.of("read_file")));
        assertThrows(DynamicToolException.class, () -> registry.create(
                spec("not_a_tool", "public class NotATool { }"), Set.of()));
        assertThrows(DynamicToolException.class, () -> registry.create(spec("no_class", "int x;"), Set.of()));
    }

    @Test
    void supportsPackagesAndHelperClasses() throws Exception {
        String source = """
                package agent.tools;

                import java.util.Map;
                import net.talaatharb.harness.tools.dynamic.DynamicTool;

                public final class Reverse implements DynamicTool {
                    static final class Helper {
                        static String reverse(String s) { return new StringBuilder(s).reverse().toString(); }
                    }
                    public String execute(Map<String, Object> arguments) {
                        return Helper.reverse(String.valueOf(arguments.get("text")));
                    }
                }
                """;
        Tool tool = new DynamicToolRegistry(dir).create(spec("reverse", source), Set.of());
        assertEquals("cba", tool.execute(Map.of("text", "abc")).output());
    }

    @Test
    void executionFailuresAndTimeoutsBecomeErrorResults() throws Exception {
        DynamicToolRegistry registry = new DynamicToolRegistry(dir, Duration.ofMillis(300));
        Tool failing = registry.create(spec("failing", """
                public class Failing implements net.talaatharb.harness.tools.dynamic.DynamicTool {
                    public String execute(java.util.Map<String, Object> a) { throw new IllegalStateException("boom"); }
                }
                """), Set.of());
        Tool slow = registry.create(spec("slow", """
                public class Slow implements net.talaatharb.harness.tools.dynamic.DynamicTool {
                    public String execute(java.util.Map<String, Object> a) throws Exception {
                        Thread.sleep(10_000);
                        return "late";
                    }
                }
                """), Set.of());

        ToolResult failed = failing.execute(Map.of());
        assertFalse(failed.success());
        assertTrue(failed.output().contains("IllegalStateException: boom"));
        ToolResult timedOut = slow.execute(Map.of());
        assertFalse(timedOut.success());
        assertTrue(timedOut.output().contains("timed out"));
    }

    @Test
    void createToolDerivesMissingNameAndDescription() {
        DynamicToolSpec spec = new DynamicToolSpec(null, null, null,
                "package x;\npublic class FibonacciTool implements net.talaatharb.harness.tools.dynamic.DynamicTool {}");
        CreateToolTool.applyDefaults(spec);
        assertEquals("fibonacci_tool", spec.name);
        assertTrue(spec.description.contains("fibonacci_tool"));
    }

    @Test
    void importsDynamicToolAndJavaUtilImplicitly() throws Exception {
        String source = "public class Size implements DynamicTool {\n"
                + "  public String execute(Map<String, Object> a) { List<Object> l = new ArrayList<>(a.keySet());"
                + " return String.valueOf(l.size()); }\n}";
        Tool tool = new DynamicToolRegistry(dir).create(spec("size", source), Set.of());
        assertEquals("2", tool.execute(Map.of("a", 1, "b", 2)).output());
        assertEquals(source, new DynamicToolRegistry(dir).spec("size").orElseThrow().sourceCode);

        DynamicToolException e = assertThrows(DynamicToolException.class, () -> new DynamicToolRegistry(dir)
                .create(spec("broken", "package p;\npublic class B implements DynamicTool {\n  int x = \"s\";\n}"),
                        Set.of()));
        assertTrue(e.getMessage().contains("line 3"), e.getMessage());
    }

    @Test
    void replacesAndRemovesTools() throws Exception {
        DynamicToolRegistry registry = new DynamicToolRegistry(dir);
        registry.create(spec("word_count", WORD_COUNT), Set.of());
        Tool replaced = registry.create(spec("word_count", WORD_COUNT.replace("String.valueOf(text.isBlank()",
                "\"n=\" + String.valueOf(text.isBlank()")), Set.of());

        assertEquals(1, registry.tools().size());
        assertEquals("n=1", replaced.execute(Map.of("text", "x")).output());
        assertTrue(registry.remove("word_count"));
        assertTrue(registry.tools().isEmpty());
        assertFalse(Files.exists(dir.resolve("word_count.json")));
    }
}