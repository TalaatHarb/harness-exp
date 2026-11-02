package net.talaatharb.harness.config;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.google.inject.Guice;
import com.google.inject.Injector;
import com.google.inject.Key;
import com.google.inject.name.Names;

import net.talaatharb.harness.skills.SkillRepository;
import net.talaatharb.harness.tools.dynamic.DynamicToolRegistry;

class StoragePathsTest {
    @TempDir
    Path temp;

    @Test
    void blankUsesDefaultsNextToSettings() {
        StoragePaths paths = StoragePaths.resolve(temp.resolve("settings.json"), new StorageSettings());
        assertEquals(temp.resolve("skills"), paths.skillsDir());
        assertEquals(temp.resolve("dynamic-tools"), paths.dynamicToolsDir());
        assertEquals(temp.resolve("history.db"), paths.historyDb());
        assertEquals(paths, StoragePaths.resolve(temp.resolve("settings.json"), null));
    }

    @Test
    void resolvesAbsoluteRelativeHomeAndInvalidPaths() {
        Path external = temp.resolve("shared").resolve("skills");
        StorageSettings s = new StorageSettings();
        s.skillsDir = "  " + external + "  ";
        s.dynamicToolsDir = "../tools";
        s.historyDb = "~/chats.db";
        StoragePaths paths = StoragePaths.resolve(temp.resolve("conf").resolve("settings.json"), s);

        assertEquals(external, paths.skillsDir());
        assertEquals(temp.resolve("tools"), paths.dynamicToolsDir());
        assertEquals(Path.of(System.getProperty("user.home"), "chats.db").toAbsolutePath().normalize(),
                paths.historyDb());
        assertEquals(temp.resolve("x").resolve("history.db"),
                StoragePaths.resolve(temp.resolve("x"), "bad\0path", "history.db"));
    }

    @Test
    void injectorUsesConfiguredFolders() throws Exception {
        Path settingsFile = temp.resolve("settings.json");
        Path skills = temp.resolve("elsewhere").resolve("my-skills");
        Path tools = temp.resolve("elsewhere").resolve("my-tools");
        Files.writeString(settingsFile, "{\"storage\":{\"skillsDir\":\"" + skills.toString().replace("\\", "\\\\")
                + "\",\"dynamicToolsDir\":\"" + tools.toString().replace("\\", "\\\\") + "\"}}");

        Injector injector = Guice.createInjector(new AppModule(settingsFile));

        assertEquals(skills, injector.getInstance(SkillRepository.class).dir());
        assertEquals(tools, injector.getInstance(Key.get(Path.class, Names.named(DynamicToolRegistry.STORAGE_DIR))));
        assertEquals(temp.resolve("history.db"), injector.getInstance(StoragePaths.class).historyDb());
        assertEquals(true, Files.isRegularFile(skills.resolve("summarizer").resolve("SKILL.md")));
    }
}
