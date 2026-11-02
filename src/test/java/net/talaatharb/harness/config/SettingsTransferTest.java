package net.talaatharb.harness.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import net.talaatharb.harness.skills.SkillDefinition;
import net.talaatharb.harness.skills.SkillRepository;
import net.talaatharb.harness.tools.builtin.CurrentTimeTool;
import net.talaatharb.harness.tools.dynamic.DynamicToolRegistry;
import net.talaatharb.harness.tools.dynamic.DynamicToolSpec;

class SettingsTransferTest {
    private static final String SOURCE = """
            import java.util.Map;
            import net.talaatharb.harness.tools.dynamic.DynamicTool;

            public class Echo implements DynamicTool {
                public String execute(Map<String, Object> arguments) {
                    return String.valueOf(arguments.get("text"));
                }
            }
            """;

    @TempDir
    Path dir;

    private record Env(SettingsStore store, SkillRepository skills, DynamicToolRegistry tools,
            SettingsTransfer transfer) {
    }

    private Env env(String name) {
        Path base = dir.resolve(name);
        SettingsStore store = new SettingsStore(base.resolve("settings.json"));
        SkillRepository skills = new SkillRepository(base.resolve("skills"), store);
        DynamicToolRegistry tools = new DynamicToolRegistry(base.resolve("tools"));
        return new Env(store, skills, tools,
                new SettingsTransfer(store, skills, tools, () -> Set.of(new CurrentTimeTool())));
    }

    @Test
    void roundTripsSettingsSkillsAndToolsWithoutTheApiKey() throws Exception {
        Env source = env("source");
        HarnessSettings s = source.store().copy();
        s.model.model = "llama3.2";
        s.model.apiKey = "secret-key";
        s.systemPrompt.template = "You are a pirate.";
        s.guardrails.blockedTerms.add("parrot");
        source.store().replace(s);
        source.skills().save(new SkillDefinition("Haiku", "Writes haiku", "Answer in 5-7-5.", List.of("haiku"), true));
        source.tools().create(new DynamicToolSpec("echo", "Echoes text", "{\"text\":\"…\"}", SOURCE), Set.of());
        Path file = dir.resolve("export.json");

        source.transfer().write(source.transfer().export(source.store().current(),
                SettingsTransfer.ExportOptions.defaults()), file);
        String json = Files.readString(file);
        assertFalse(json.contains("secret-key"));
        assertTrue(json.contains(SettingsBundle.FORMAT));

        Env target = env("target");
        HarnessSettings mine = target.store().copy();
        mine.model.apiKey = "my-key";
        mine.storage.skillsDir = "D:/my-skills";
        target.store().replace(mine);
        SettingsBundle bundle = target.transfer().read(file);
        SettingsTransfer.ImportReport report = target.transfer().apply(bundle,
                new SettingsTransfer.ImportOptions(true, true, true, true, true));

        assertTrue(report.errors().isEmpty(), report.summary());
        HarnessSettings now = target.store().current();
        assertEquals("llama3.2", now.model.model);
        assertEquals("my-key", now.model.apiKey);
        assertEquals("D:/my-skills", now.storage.skillsDir);
        assertEquals("You are a pirate.", now.systemPrompt.template);
        assertTrue(now.guardrails.blockedTerms.contains("parrot"));
        assertTrue(target.skills().findByName("Haiku").isPresent());
        assertTrue(target.tools().spec("echo").isPresent());
    }

    @Test
    void dynamicToolsAreOnlyImportedWhenAskedAndErrorsAreReported() throws Exception {
        Env target = env("target");
        SettingsBundle bundle = new SettingsBundle();
        bundle.dynamicTools.add(new DynamicToolSpec("echo", "Echoes text", "", SOURCE));
        bundle.dynamicTools.add(new DynamicToolSpec("current_time", "Clash", "", SOURCE));

        target.transfer().apply(bundle, SettingsTransfer.ImportOptions.defaults());
        assertTrue(target.tools().tools().isEmpty());

        SettingsTransfer.ImportReport report = target.transfer().apply(bundle,
                new SettingsTransfer.ImportOptions(false, true, true, false, true));
        assertTrue(target.tools().spec("echo").isPresent());
        assertEquals(1, report.errors().size());
        assertTrue(report.errors().get(0).contains("current_time"));
    }

    @Test
    void readsPlainSettingsFilesAndRejectsOtherJson() throws IOException {
        Env target = env("target");
        Path plain = dir.resolve("settings.json");
        Files.writeString(plain, """
                {"model":{"model":"phi4"},"skills":[{"name":"Old","instructions":"legacy"}]}
                """);
        SettingsBundle bundle = target.transfer().read(plain);
        assertEquals("phi4", bundle.settings.model.model);
        assertEquals(1, bundle.skills.size());
        assertNull(bundle.settings.skills);

        Path other = dir.resolve("other.json");
        Files.writeString(other, "{\"hello\":1}");
        assertThrows(IOException.class, () -> target.transfer().read(other));
        Files.writeString(other, "{\"format\":\"harness-exp/settings\",\"version\":99}");
        assertThrows(IOException.class, () -> target.transfer().read(other));
    }
}
