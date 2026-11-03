package net.talaatharb.harness.memory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import net.talaatharb.harness.config.HarnessSettings;
import net.talaatharb.harness.context.ContextAssembly;
import net.talaatharb.harness.tools.ToolResult;

class MemoryTest {
    @TempDir
    Path dir;

    private final MemorySettings settings = new MemorySettings();
    private MemoryService service;

    private MemoryService service(MemorySettings.Backend backend) {
        settings.backend = backend;
        service = new MemoryService(() -> settings, new MarkdownMemoryStore(dir.resolve("memory")),
                new MapDbMemoryStore(dir.resolve("memory.db")));
        return service;
    }

    @AfterEach
    void close() {
        if (service != null) {
            service.close();
        }
    }

    @ParameterizedTest
    @EnumSource(MemorySettings.Backend.class)
    void storesEditsAndDeletesMemories(MemorySettings.Backend backend) throws Exception {
        MemoryStore store = service(backend).active();
        MemoryEntry saved = store.save(new MemoryEntry("The user prefers metric units.\nAlways.",
                List.of("Preferences", "#units"), MemoryEntry.SOURCE_USER));
        assertNotNull(saved.id);
        assertTrue(saved.id.startsWith("the-user-prefers-metric-units-"), saved.id);
        assertEquals(List.of("preferences", "units"), saved.tags);
        assertNotNull(saved.createdAt);

        MemoryEntry read = store.get(saved.id).orElseThrow();
        assertEquals("The user prefers metric units.\nAlways.", read.text);
        assertEquals(saved.tags, read.tags);
        assertEquals(MemoryEntry.SOURCE_USER, read.source);

        Thread.sleep(5);
        read.text = "The user prefers imperial units.";
        MemoryEntry edited = store.save(read);
        assertEquals(saved.id, edited.id);
        assertEquals(saved.createdAt, edited.createdAt);
        assertTrue(edited.updatedAt.isAfter(saved.updatedAt));

        MemoryEntry second = store.save(new MemoryEntry("Second", List.of(), MemoryEntry.SOURCE_AGENT));
        assertEquals(List.of(second.id, saved.id), store.list().stream().map(m -> m.id).toList());
        assertTrue(store.delete(saved.id));
        assertFalse(store.delete(saved.id));
        assertEquals(1, store.list().size());
        assertTrue(store.get("../../etc/passwd").isEmpty());
    }

    @Test
    void markdownFilesAreReadableAndHandEditable() throws Exception {
        MarkdownMemoryStore store = (MarkdownMemoryStore) service(MemorySettings.Backend.MARKDOWN).active();
        MemoryEntry saved = store.save(new MemoryEntry("Project codename is Falcon", List.of("project"),
                MemoryEntry.SOURCE_AGENT));
        String file = Files.readString(store.file(saved.id));
        assertTrue(file.startsWith("---\ntags: [\"project\"]\nsource: agent\ncreated: "), file);
        assertTrue(file.endsWith("---\n\nProject codename is Falcon\n"), file);

        Files.writeString(store.dir().resolve("by-hand.md"), "\uFEFFThe office is in Cairo.\r\n");
        Files.writeString(store.dir().resolve("notes.txt"), "ignored");
        MemoryEntry byHand = store.get("by-hand").orElseThrow();
        assertEquals("The office is in Cairo.", byHand.text);
        assertEquals(List.of(), byHand.tags);
        assertNotNull(byHand.updatedAt);
        assertEquals(2, store.list().size());

        MemoryEntry parsed = MarkdownMemoryStore.parse("---\ntags: a, #B\nsource: 'user'\ncreated: nope\n---\nText");
        assertEquals(List.of("a", "b"), parsed.tags);
        assertEquals("user", parsed.source);
        assertEquals("Text", parsed.text);
        assertThrows(IllegalArgumentException.class, () -> MarkdownMemoryStore.checkId("..\\x"));
    }

    @ParameterizedTest
    @EnumSource(MemorySettings.Backend.class)
    void rememberSkipsDuplicatesAndExtendsCoveredMemories(MemorySettings.Backend backend) throws Exception {
        MemoryService memory = service(backend);
        var added = memory.remember("The user's favourite language is Kotlin", List.of("languages"), null, "agent");
        assertEquals(MemoryService.Outcome.ADDED, added.outcome());

        var same = memory.remember("the user's favourite language is kotlin!", List.of(), null, "agent");
        assertEquals(MemoryService.Outcome.DUPLICATE, same.outcome());
        assertEquals(added.entry().id, same.entry().id);

        var covered = memory.remember("Favourite language: Kotlin", List.of("prefs"), null, "agent");
        assertEquals(MemoryService.Outcome.DUPLICATE, covered.outcome());
        assertEquals(List.of("languages", "prefs"), memory.active().get(added.entry().id).orElseThrow().tags);

        var extended = memory.remember("The user's favourite language is Kotlin, mostly for Android apps",
                List.of(), null, "agent");
        assertEquals(MemoryService.Outcome.UPDATED, extended.outcome());
        assertEquals(added.entry().id, extended.entry().id);
        assertEquals("The user's favourite language is Kotlin", extended.previous().text);

        var different = memory.remember("The user lives in Alexandria", List.of(), null, "agent");
        assertEquals(MemoryService.Outcome.ADDED, different.outcome());

        var replaced = memory.remember("The user moved to Cairo", List.of("location"), different.entry().id, "user");
        assertEquals(MemoryService.Outcome.UPDATED, replaced.outcome());
        assertEquals("The user moved to Cairo", memory.active().get(different.entry().id).orElseThrow().text);
        assertEquals(2, memory.active().list().size());

        assertThrows(IllegalArgumentException.class, () -> memory.remember(" ", List.of(), null, "agent"));
        assertThrows(IllegalArgumentException.class, () -> memory.remember("x", List.of(), "missing-id", "agent"));
        assertThrows(IllegalArgumentException.class,
                () -> memory.remember("x".repeat(MemoryService.MAX_MEMORY_CHARS + 1), List.of(), null, "agent"));
    }

    @Test
    void recallRanksByRareWordsAndTags() throws Exception {
        MemoryService memory = service(MemorySettings.Backend.MAPDB);
        memory.remember("The user's name is Mohamed", List.of("profile"), null, "agent");
        memory.remember("The user prefers dark themes in editors", List.of(), null, "agent");
        memory.remember("The user's cats are called Mishmish and Loz", List.of("pets"), null, "agent");
        memory.remember("The user works with Java and JavaFX", List.of(), null, "agent");

        List<MemoryService.Match> name = memory.recall("What is my name?", 5);
        assertEquals(1, name.size());
        assertTrue(name.get(0).entry().text.contains("Mohamed"));

        assertTrue(memory.recall("cat", 5).get(0).entry().text.contains("Mishmish"));
        assertTrue(memory.recall("pets", 5).get(0).entry().text.contains("Mishmish"));
        assertEquals(1, memory.recall("Tell me about the user's pets and themes", 1).size());
        assertTrue(memory.recall("quantum chromodynamics", 5).isEmpty());
        assertEquals(4, memory.recall("", 10).size());
        assertEquals(Set.of("user", "cat", "called"), MemoryService.terms("The user's cats are called"));
    }

    @Test
    void toolsUseTheActiveBackend() throws Exception {
        MemoryService memory = service(MemorySettings.Backend.MARKDOWN);
        RememberTool remember = new RememberTool(memory);
        RecallTool recall = new RecallTool(memory);

        ToolResult first = remember.execute(Map.of("text", "The user prefers tea over coffee",
                "tags", List.of("drinks")));
        assertTrue(first.success(), first.output());
        assertTrue(first.output().startsWith("Remembered as ["), first.output());
        ToolResult again = remember.execute(Map.of("text", "User prefers tea over coffee", "tags", "drinks"));
        assertTrue(again.output().startsWith("Already remembered"), again.output());
        assertFalse(remember.execute(Map.of()).success());

        ToolResult found = recall.execute(Map.of("query", "tea", "limit", 3));
        assertTrue(found.output().startsWith("1 memory (most relevant first):\n- ["), found.output());
        assertTrue(found.output().contains("tags: drinks"), found.output());
        assertEquals("No memories match \"pizza\".", recall.execute(Map.of("query", "pizza")).output());
        assertFalse(recall.execute(Map.of("limit", "lots")).success());

        settings.backend = MemorySettings.Backend.MAPDB;
        assertEquals("Memory is empty.", recall.execute(Map.of()).output());
    }

    @Test
    void contributorInjectsRelevantMemoriesOnly() throws Exception {
        MemoryService memory = service(MemorySettings.Backend.MARKDOWN);
        memory.remember("The user's name is Mohamed", List.of(), null, "agent");
        memory.remember("Deployments happen on Thursdays", List.of(), null, "agent");
        MemoryContextContributor contributor = new MemoryContextContributor(memory);
        HarnessSettings s = new HarnessSettings();

        ContextAssembly a = new ContextAssembly(s, List.of(), "When do deployments happen?");
        contributor.contribute(a);
        assertEquals(1, a.systemSections().size());
        String section = a.systemSections().get(0);
        assertTrue(section.startsWith("## Memory\n"), section);
        assertTrue(section.contains("Deployments happen on Thursdays"), section);
        assertFalse(section.contains("Mohamed"), section);

        ContextAssembly unrelated = new ContextAssembly(s, List.of(), "Hello there");
        contributor.contribute(unrelated);
        assertTrue(unrelated.systemSections().isEmpty());

        s.memory.maxContextMemories = 0;
        ContextAssembly off = new ContextAssembly(s, List.of(), "deployments");
        contributor.contribute(off);
        assertTrue(off.systemSections().isEmpty());

        s.memory.maxContextMemories = 5;
        s.memory.backend = MemorySettings.Backend.MAPDB;
        ContextAssembly otherBackend = new ContextAssembly(s, List.of(), "deployments");
        contributor.contribute(otherBackend);
        assertTrue(otherBackend.systemSections().isEmpty());
    }
}
