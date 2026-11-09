package net.talaatharb.harness.memory;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import net.talaatharb.harness.config.HarnessSettings;
import net.talaatharb.harness.context.ContextAssembly;
import net.talaatharb.harness.tools.RiskLevel;

class MemoryImprovementsTest {
    @TempDir Path dir;

    private MemoryService service(MemorySettings.Backend backend) {
        MemorySettings settings = new MemorySettings();
        settings.backend = backend;
        return new MemoryService(() -> settings, new MarkdownMemoryStore(dir.resolve("memory")),
                new MapDbMemoryStore(dir.resolve("memory.db")));
    }

    @ParameterizedTest
    @EnumSource(MemorySettings.Backend.class)
    void batchSavesAllFactsWithExactContentsAndStaysApprovalGated(MemorySettings.Backend backend) throws Exception {
        try (MemoryService memory = service(backend)) {
            RememberTool tool = new RememberTool(memory);
            assertEquals(RiskLevel.SENSITIVE, tool.risk());
            String text = "A self-contained long fact: " + "detail ".repeat(80) + "\nFinal detail.";
            var result = tool.execute(Map.of("facts", List.of(
                    Map.of("text", text, "tags", List.of("long")), "The user's dog is named Luna"),
                    "tags", "profile"));
            assertTrue(result.success(), result.output());
            assertTrue(result.output().contains(text), result.output());
            assertTrue(result.output().contains("The user's dog is named Luna"), result.output());
            assertEquals(2, memory.active().list().size());
            MemoryEntry saved = memory.active().list().stream().filter(e -> e.text.equals(text)).findFirst()
                    .orElseThrow();
            assertEquals(List.of("profile", "long"), saved.tags);
            var duplicate = tool.execute(Map.of("memory", text));
            assertTrue(duplicate.success());
            assertTrue(duplicate.output().contains(text));
            var update = tool.execute(Map.of("text", text + " Updated.", "replace_id", saved.id));
            assertTrue(update.success());
            assertTrue(update.output().contains(text + " Updated."));
        }
    }

    @Test
    void ignoresUnusedOptionalFieldsButPreservesLiteralFactText() throws Exception {
        try (MemoryService memory = service(MemorySettings.Backend.MARKDOWN)) {
            RememberTool remember = new RememberTool(memory);
            var args = new java.util.HashMap<String, Object>();
            args.put("text", "default");
            args.put("memory", null);
            args.put("facts", List.of());
            args.put("tags", "default");
            args.put("replace_id", "null");
            assertTrue(remember.execute(args).success());
            assertEquals("default", memory.active().list().get(0).text);
            assertTrue(memory.active().list().get(0).tags.isEmpty());
            var recall = new RecallTool(memory);
            assertTrue(recall.execute(Map.of("query", "default", "limit", "default")).success());
            assertFalse(recall.execute(Map.of("limit", "NaN")).success());
        }
    }

    @Test
    void validatesEntireBatchBeforeAnyWrites() throws Exception {
        try (MemoryService memory = service(MemorySettings.Backend.MARKDOWN)) {
            RememberTool tool = new RememberTool(memory);
            List<Object> badFacts = new ArrayList<>();
            badFacts.add("");
            badFacts.add(7);
            badFacts.add(Map.of("text", "x".repeat(MemoryService.MAX_MEMORY_CHARS + 1)));
            badFacts.add(Map.of("text", "valid", "tags", List.of(3)));
            badFacts.add(Map.of("text", "valid", "tags", Map.of()));
            badFacts.add(Map.of("text", "valid", "replace_id", 9));
            badFacts.add(Map.of("text", "valid", "replace_id", "missing"));
            badFacts.add(Map.of("text", "valid", "unknown", "do not ignore"));
            for (Object bad : badFacts) {
                var result = tool.execute(Map.of("facts", List.of("Must not be written", bad)));
                assertFalse(result.success(), result.output());
                assertTrue(result.output().contains("no facts written"), result.output());
                assertTrue(memory.active().list().isEmpty());
            }
            for (Map<String, Object> args : List.<Map<String, Object>>of(
                    Map.of("facts", List.of()), Map.of("facts", "wrong"),
                    Map.of("facts", java.util.Collections.nCopies(RememberTool.MAX_FACTS + 1, "fact")),
                    Map.of("facts", List.of("fact"), "text", "ambiguous"),
                    Map.of("facts", List.of("fact"), "replace_id", "ambiguous"),
                    Map.of("facts", List.of("fact"), "unknown", "do not ignore"),
                    Map.of("text", true), Map.of("text", "fact", "tags", 9))) {
                assertFalse(tool.execute(args).success(), args.toString());
                assertTrue(memory.active().list().isEmpty());
            }
            String id = memory.remember("Original fact", List.of(), null, "agent").entry().id;
            var repeatedId = tool.execute(Map.of("facts", List.of(
                    Map.of("text", "First replacement", "replace_id", id),
                    Map.of("text", "Second replacement", "replace_id", id))));
            assertFalse(repeatedId.success());
            assertEquals("Original fact", memory.active().get(id).orElseThrow().text);
        }
    }

    @Test
    void reportsPartialFailureAndKeepsSuccessfulWritesWithoutPretendingRollback() throws Exception {
        MarkdownMemoryStore failing = new MarkdownMemoryStore(dir.resolve("partial")) {
            private int count;

            @Override
            public synchronized MemoryEntry save(MemoryEntry entry) throws IOException {
                if (++count == 2) {
                    throw new IOException("injected disk failure");
                }
                return super.save(entry);
            }
        };
        try (MemoryService memory = new MemoryService(MemorySettings::new, failing, failing)) {
            var result = new RememberTool(memory).execute(Map.of("facts", List.of("First fact", "Second fact",
                    "Third fact")));
            assertFalse(result.success());
            assertTrue(result.output().contains("First fact"));
            assertTrue(result.output().contains("Fact 2: FAILED"));
            assertTrue(result.output().contains("injected disk failure"));
            assertTrue(result.output().contains("Third fact"));
            assertTrue(result.output().contains("2 of 3"));
            assertTrue(result.output().contains("NOT rolled back"));
            assertEquals(List.of("Third fact", "First fact"), failing.list().stream().map(e -> e.text).toList());
        }
    }

    @ParameterizedTest
    @EnumSource(MemorySettings.Backend.class)
    void overlapCannotDiscardDifferentNamesNegationNumbersOrOrder(MemorySettings.Backend backend) throws Exception {
        try (MemoryService memory = service(backend)) {
            List<String> distinct = List.of(
                    "The user's beloved family dog is named Luna and lives in Cairo",
                    "The user's beloved family dog is named Bella and lives in Cairo",
                    "The user likes coffee", "The user does not like coffee",
                    "The user takes 10 mg daily", "The user takes 20 mg daily",
                    "Alice follows Bob", "Bob follows Alice",
                    "The user writes C++", "The user writes C#",
                    "The user's favourite language is Kotlin",
                    "The user's favourite language is Kotlin for Android");
            for (String text : distinct) {
                assertEquals(MemoryService.Outcome.ADDED, memory.remember(text, List.of(), null, "agent").outcome());
            }
            assertEquals(distinct.size(), memory.active().list().size());
        }
    }

    @Test
    void recallsConservativeUnitsParaphrasesWithoutGenericPreferenceMatches() {
        MemoryEntry metric = new MemoryEntry("The user prefers metric", List.of(), "agent");
        MemoryEntry theme = new MemoryEntry("The user prefers dark editor themes", List.of(), "agent");
        MemoryEntry performance = new MemoryEntry("Track the performance metric latency", List.of(), "agent");
        List<MemoryEntry> entries = List.of(metric, theme, performance);
        for (String query : List.of("What are my preferred units?", "Which measurement system do I prefer?",
                "metric system", "units")) {
            assertEquals(List.of(metric), MemoryService.rank(entries, query, 10).stream()
                    .map(MemoryService.Match::entry).toList(), query);
        }
        assertTrue(MemoryService.rank(entries, "What do I prefer?", 10).isEmpty());
        assertTrue(MemoryService.rank(entries, "quantum chromodynamics", 10).isEmpty());
        assertTrue(MemoryService.rank(entries, "Hello user", 10).isEmpty());
        assertEquals(theme, MemoryService.rank(entries, "editor themes", 10).get(0).entry());
    }

    @Test
    void retrievalFailuresAreVisibleInContextAndNotMistakenForEmptyMemory() throws Exception {
        Path blocked = dir.resolve("not-a-directory");
        Files.writeString(blocked, "file");
        MemoryStore markdown = new MarkdownMemoryStore(blocked);
        try (MemoryService memory = new MemoryService(MemorySettings::new, markdown, markdown)) {
            ContextAssembly assembly = new ContextAssembly(new HarnessSettings(), List.of(), "units");
            new MemoryContextContributor(memory).contribute(assembly);
            assertEquals(1, assembly.systemSections().size());
            assertTrue(assembly.systemSections().get(0).contains("Memory retrieval failed"));
            assertFalse(new RememberTool(memory).execute(Map.of("text", "fact")).success());
        }
        try (MapDbMemoryStore mapdb = new MapDbMemoryStore(blocked.resolve("memory.db"))) {
            assertThrows(IllegalStateException.class, mapdb::list);
            assertThrows(IllegalStateException.class,
                    () -> mapdb.save(new MemoryEntry("Must not become volatile success", List.of(), "agent")));
        }
    }
}
