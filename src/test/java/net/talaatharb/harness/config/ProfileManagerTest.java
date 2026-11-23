package net.talaatharb.harness.config;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.google.inject.Guice;

class ProfileManagerTest {
    @TempDir Path temp;

    @Test
    void preservesLegacySettingsAndUsesIndependentProfileStorage() throws Exception {
        Path legacy = temp.resolve("settings.json");
        String old = "{\"model\":{\"model\":\"my-model\"},\"storage\":{\"historyDb\":\"old/history.db\"}}";
        Files.writeString(legacy, old);
        ProfileManager manager = new ProfileManager(legacy);
        manager.initialize();

        assertEquals(old, Files.readString(legacy));
        assertEquals(List.of("default", "coding", "learning"), manager.profiles());
        assertEquals(legacy, manager.runningSettingsPath());
        assertEquals(temp.resolve("old").resolve("history.db"),
                Guice.createInjector(new AppModule(manager.runningSettingsPath(), manager))
                        .getInstance(StoragePaths.class).historyDb());
        HarnessSettings learning = new SettingsStore(manager.settingsPath("learning")).current();
        HarnessSettings coding = new SettingsStore(manager.settingsPath("coding")).current();
        assertTrue(learning.systemPrompt.template.contains("learning assistant"));
        assertTrue(learning.tools.disabledTools.contains("edit_file"));
        assertFalse(coding.tools.disabledTools.contains("edit_file"));
        assertTrue(coding.systemPrompt.template.contains("software engineering"));
        for (HarnessSettings profile : List.of(new SettingsStore(legacy).current(), learning, coding)) {
            assertTrue(profile.systemPrompt.template.contains("```mermaid"), "missing diagram guidance");
        }
        var learningSkills = new net.talaatharb.harness.skills.SkillRepository(
                manager.settingsPath("learning").resolveSibling("skills"), null);
        var codingSkills = new net.talaatharb.harness.skills.SkillRepository(
                manager.settingsPath("coding").resolveSibling("skills"), null);
        var tutor = learningSkills.find("interactive-tutor").orElseThrow();
        assertTrue(tutor.appliesTo("teach me recursion"), "tutor should be always on");
        assertTrue(tutor.instructions.contains("ask_user_for_decision"));
        assertTrue(learningSkills.find("summarizer").isPresent(), "general defaults are kept");
        assertTrue(learningSkills.find("refactoring").isEmpty());
        assertTrue(codingSkills.find("feature-development").orElseThrow().appliesTo("Implement CSV export"));
        assertTrue(codingSkills.find("refactoring").orElseThrow().appliesTo("Please refactor the parser"));
        assertTrue(codingSkills.find("interactive-tutor").isEmpty());
        assertFalse(Files.exists(legacy.resolveSibling("skills")), "default profile skills are seeded lazily");
        assertEquals("", learning.model.apiKey);

        manager.select("learning");
        assertEquals("default", manager.running());
        ProfileManager restarted = new ProfileManager(legacy);
        restarted.initialize();
        assertEquals("learning", restarted.running());
        var injector = Guice.createInjector(new AppModule(restarted.runningSettingsPath(), restarted));
        StoragePaths paths = injector.getInstance(StoragePaths.class);
        assertEquals(temp.resolve("profiles").resolve("learning").resolve("history.db"), paths.historyDb());
        assertEquals(temp.resolve("profiles").resolve("learning").resolve("memory.db"), paths.memoryDb());
        assertEquals(temp.resolve("profiles").resolve("learning").resolve("rag-index"), paths.ragIndex());
        HarnessSettings changed = injector.getInstance(SettingsStore.class).copy();
        changed.model.model = "learning-model";
        injector.getInstance(SettingsStore.class).replace(changed);
        assertEquals("my-model", new SettingsStore(legacy).current().model.model);
        assertEquals("learning-model", new SettingsStore(restarted.runningSettingsPath()).current().model.model);
        restarted.initialize();
        assertEquals("learning-model", new SettingsStore(restarted.runningSettingsPath()).current().model.model);
    }

    @Test
    void createsProfilesAndRejectsInvalidOrMissingSelectionsWithoutChangingTheCurrentOne() throws Exception {
        ProfileManager manager = new ProfileManager(temp.resolve("settings.json"));
        manager.initialize();
        assertEquals("my_project", manager.create("My_Project"));
        assertTrue(manager.profiles().contains("my_project"));
        assertThrows(IllegalArgumentException.class, () -> manager.create("../other"));
        assertThrows(IllegalArgumentException.class, () -> manager.create("default"));
        assertThrows(java.nio.file.FileAlreadyExistsException.class, () -> manager.create("coding"));
        manager.select("my_project");
        assertThrows(java.io.IOException.class, () -> manager.select("missing"));
        assertThrows(IllegalArgumentException.class, () -> manager.select("../other"));
        assertEquals("my_project", manager.selected());
        Files.writeString(manager.settingsPath("my_project"), "{invalid");
        assertThrows(java.io.IOException.class, () -> new ProfileManager(temp.resolve("settings.json")).initialize());
        assertEquals("{invalid", Files.readString(manager.settingsPath("my_project")));
    }

    @Test
    void doesNotOverwriteBrokenInactiveProfilesOrConfuseCustomSettingsFiles() throws Exception {
        Path settings = temp.resolve("custom.json");
        ProfileManager manager = new ProfileManager(settings);
        manager.initialize();
        assertEquals(temp.resolve("custom.json.profiles").resolve("coding").resolve("settings.json"),
                manager.settingsPath("coding"));
        Files.writeString(manager.settingsPath("coding"), "{invalid");
        ProfileManager restarted = new ProfileManager(settings);
        restarted.initialize();
        assertEquals("default", restarted.running());
        assertThrows(java.io.IOException.class, () -> restarted.select("coding"));
        assertEquals("{invalid", Files.readString(manager.settingsPath("coding")));
    }

    @Test
    void reportsCorruptSelectionInsteadOfSilentlySwitchingProfiles() throws Exception {
        ProfileManager manager = new ProfileManager(temp.resolve("settings.json"));
        manager.initialize();
        Files.writeString(temp.resolve("profiles").resolve("active-profile"), "../invalid");
        assertThrows(java.io.IOException.class, manager::selected);
        assertThrows(java.io.IOException.class,
                () -> new ProfileManager(temp.resolve("settings.json")).initialize());
    }

    @Test
    void historyMemoryAndRagStayIsolatedAndPersistAcrossReopen() throws Exception {
        ProfileManager manager = new ProfileManager(temp.resolve("settings.json"));
        manager.initialize();
        var defaults = Guice.createInjector(new AppModule(manager.settingsPath("default"), manager));
        var learning = Guice.createInjector(new AppModule(manager.settingsPath("learning"), manager));
        var defaultHistory = defaults.getInstance(net.talaatharb.harness.history.ConversationStore.class);
        var learningHistory = learning.getInstance(net.talaatharb.harness.history.ConversationStore.class);
        var defaultMemory = defaults.getInstance(net.talaatharb.harness.memory.MemoryService.class);
        var learningMemory = learning.getInstance(net.talaatharb.harness.memory.MemoryService.class);
        var defaultRag = defaults.getInstance(net.talaatharb.harness.rag.RagService.class);
        var learningRag = learning.getInstance(net.talaatharb.harness.rag.RagService.class);
        var settings = learning.getInstance(SettingsStore.class).copy();
        settings.rag.embedder = net.talaatharb.harness.rag.RagSettings.EmbedderType.BUILT_IN;
        learning.getInstance(SettingsStore.class).replace(settings);
        var record = net.talaatharb.harness.history.ConversationRecord.create();
        var defaultRecord = net.talaatharb.harness.history.ConversationRecord.create();
        try {
            assertTrue(defaultHistory.persistent());
            assertTrue(learningHistory.persistent());
            defaultHistory.save(defaultRecord);
            learningHistory.save(record);
            learningMemory.remember("A learning profile memory", List.of(), null, "test");
            learningRag.ingestText(record.id, "note", "text", "A profile-specific document", null);
            assertTrue(defaultHistory.load(record.id).isEmpty());
            assertTrue(learningHistory.load(defaultRecord.id).isEmpty());
            assertTrue(defaultMemory.active().list().isEmpty());
            assertTrue(defaultRag.documents(record.id).isEmpty());
        } finally {
            defaultHistory.close();
            learningHistory.close();
            defaultMemory.close();
            learningMemory.close();
            defaultRag.close();
            learningRag.close();
        }
        var reopened = Guice.createInjector(new AppModule(manager.settingsPath("learning"), manager));
        try {
            assertTrue(reopened.getInstance(net.talaatharb.harness.history.ConversationStore.class)
                    .load(record.id).isPresent());
            assertEquals(1, reopened.getInstance(net.talaatharb.harness.memory.MemoryService.class).active().list().size());
            assertEquals(1, reopened.getInstance(net.talaatharb.harness.rag.RagService.class).documents(record.id).size());
        } finally {
            reopened.getInstance(net.talaatharb.harness.history.ConversationStore.class).close();
            reopened.getInstance(net.talaatharb.harness.memory.MemoryService.class).close();
            reopened.getInstance(net.talaatharb.harness.rag.RagService.class).close();
        }
    }
}
