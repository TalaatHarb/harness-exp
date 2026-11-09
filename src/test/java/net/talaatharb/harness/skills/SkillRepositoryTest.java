package net.talaatharb.harness.skills;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import net.talaatharb.harness.config.HarnessSettings;
import net.talaatharb.harness.config.SettingsStore;

class SkillRepositoryTest {
    @TempDir
    Path temp;

    private Path skillsDir() {
        return temp.resolve("skills");
    }

    @Test
    void parsesFrontMatterVariantsAndRoundTrips() {
        SkillDefinition parsed = SkillFiles.parse("\uFEFF---\r\nname: 'SQL: reviewer'\r\ndescription: \"Checks \\\"SQL\\\"\"\r\n"
                + "triggers:\r\n  - sql\r\n  - query\r\nenabled: off\r\n---\r\n\r\nLook for injection.\r\n", "fallback");
        assertEquals("SQL: reviewer", parsed.name);
        assertEquals("Checks \"SQL\"", parsed.description);
        assertEquals(List.of("sql", "query"), parsed.triggers);
        assertFalse(parsed.enabled);
        assertEquals("Look for injection.", parsed.instructions.strip());

        SkillDefinition again = SkillFiles.parse(SkillFiles.format(parsed), "x");
        assertEquals(parsed.name, again.name);
        assertEquals(parsed.description, again.description);
        assertEquals(parsed.triggers, again.triggers);
        assertEquals(parsed.enabled, again.enabled);
        assertEquals(parsed.instructions.strip(), again.instructions.strip());

        assertEquals(List.of("a", "b c"), SkillFiles.parse("---\ntriggers: a, b c\n---\nx", "f").triggers);
        SkillDefinition plain = SkillFiles.parse("Just instructions", "my-skill");
        assertEquals("my-skill", plain.name);
        assertEquals("Just instructions", plain.instructions.strip());
        assertTrue(plain.enabled);
    }

    @Test
    void slugsAreSafeFolderNames() {
        assertEquals("sql-reviewer", SkillFiles.slug("  SQL / Reviewer!! "));
        assertEquals("skill", SkillFiles.slug("???"));
        assertEquals("con-skill", SkillFiles.slug("CON"));
        assertTrue(SkillFiles.slug("x".repeat(200)).length() <= 60);
    }

    @Test
    void seedsBundledDefaultsOnFirstStartOnly() throws Exception {
        SkillRepository repo = new SkillRepository(skillsDir(), null);
        assertEquals(List.of("Code reviewer", "Summarizer"), repo.skills().stream().map(s -> s.name).toList());
        assertEquals(List.of("review", "code"), repo.findByName("code reviewer").orElseThrow().triggers);

        repo.delete("summarizer");
        assertEquals(1, new SkillRepository(skillsDir(), null).skills().size(), "deleted defaults are not re-seeded");
    }

    @Test
    void migratesLegacySettingsSkills() {
        SettingsStore store = new SettingsStore(null);
        HarnessSettings s = store.copy();
        s.skills = List.of(new SkillDefinition("Legacy one", "old", "Do the old thing", List.of("old"), false));
        store.replace(s);

        SkillRepository repo = new SkillRepository(skillsDir(), store);

        assertNull(store.current().skills);
        SkillDefinition migrated = repo.find("legacy-one").orElseThrow();
        assertEquals("Do the old thing", migrated.instructions.strip());
        assertFalse(migrated.enabled);
        assertTrue(repo.findByName("Summarizer").isEmpty(), "migration replaces seeding");
    }

    @Test
    void reloadPicksUpOutsideChangesAndReportsBrokenSkills() throws Exception {
        SkillRepository repo = new SkillRepository(skillsDir(), null);
        Path folder = Files.createDirectories(skillsDir().resolve("hand-made"));
        Files.writeString(folder.resolve("SKILL.md"), "---\nname: Hand made\ntriggers: [\"hand\"]\n---\nBy hand.\n",
                StandardCharsets.UTF_8);
        Files.writeString(Files.createDirectories(skillsDir().resolve("broken")).resolve("SKILL.md"),
                "---\nname: Broken\n---\n", StandardCharsets.UTF_8);
        Files.delete(skillsDir().resolve("summarizer").resolve("SKILL.md"));

        List<SkillDefinition> skills = repo.reload();

        assertEquals(List.of("Code reviewer", "Hand made"), skills.stream().map(s -> s.name).toList());
        assertEquals(1, repo.problems().size());
        assertTrue(repo.problems().get(0).startsWith("broken"));
    }

    @Test
    void saveCreatesReplacesByNameAndRejectsDuplicates() throws Exception {
        SkillRepository repo = new SkillRepository(skillsDir(), null);
        SkillDefinition created = repo.save(new SkillDefinition("  My   skill ", "d", "v1", List.of("x", "x", " "), true));
        assertEquals("my-skill", created.id);
        assertEquals("My skill", created.name);
        assertEquals(List.of("x"), created.triggers);

        SkillDefinition replaced = repo.save(new SkillDefinition("my skill", "d2", "v2", List.of(), true));
        assertEquals("my-skill", replaced.id);
        assertEquals(3, repo.skills().size());
        assertTrue(Files.readString(repo.file("my-skill")).contains("v2"));

        SkillDefinition rename = repo.find("my-skill").orElseThrow();
        rename.name = "Summarizer";
        assertThrows(IllegalArgumentException.class, () -> repo.save(rename));
        assertThrows(IllegalArgumentException.class,
                () -> repo.save(new SkillDefinition("No instructions", "", " ", List.of(), true)));

        SkillDefinition escape = new SkillDefinition("Evil", "", "x", List.of(), true);
        escape.id = "../evil";
        assertThrows(IllegalArgumentException.class, () -> repo.save(escape));
        assertThrows(IllegalArgumentException.class, () -> repo.delete(".."));
        assertFalse(Files.exists(temp.resolve("evil")));

        assertTrue(repo.delete("my-skill"));
        assertFalse(Files.exists(skillsDir().resolve("my-skill")));
    }

    @Test
    void createSkillIgnoresPlaceholderControlsButDoesNotStringifyInvalidInstructions() {
        SkillRepository repo = new SkillRepository(skillsDir(), null);
        CreateSkillTool tool = new CreateSkillTool(repo);
        var args = new java.util.HashMap<String, Object>();
        args.put("name", "Literal");
        args.put("instructions", "default");
        args.put("description", null);
        args.put("triggers", "default");
        assertTrue(tool.execute(args).success());
        var skill = repo.findByName("Literal").orElseThrow();
        assertEquals("default", skill.instructions);
        assertTrue(skill.triggers.isEmpty());
        args.put("instructions", java.util.Map.of("bad", "type"));
        assertFalse(tool.execute(args).success());
        assertEquals("default", repo.findByName("Literal").orElseThrow().instructions);
    }

    @Test
    void createSkillToolAcceptsCommaTriggersAndReportsErrors() {
        SkillRepository repo = new SkillRepository(skillsDir(), null);
        CreateSkillTool tool = new CreateSkillTool(repo);
        assertTrue(tool.alwaysRequiresApproval());

        var ok = tool.execute(java.util.Map.of("name", "Haiku", "description", "Writes haiku",
                "triggers", "haiku, poem", "instructions", "Answer in 5-7-5."));
        assertTrue(ok.success(), ok.output());
        assertEquals(List.of("haiku", "poem"), repo.findByName("Haiku").orElseThrow().triggers);

        var bad = tool.execute(java.util.Map.of("name", "Empty"));
        assertFalse(bad.success());
        assertTrue(bad.output().contains("instructions"), bad.output());
    }
}
