package net.talaatharb.harness.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.google.inject.Guice;

import net.talaatharb.harness.agent.AgentLoop;
import net.talaatharb.harness.agent.AgentLoopLimits;
import net.talaatharb.harness.approval.ApprovalPolicy;
import net.talaatharb.harness.context.ContextBuilder;

class SettingsStoreTest {

    @Test
    void roundTripsSettingsToDisk(@TempDir Path dir) {
        Path file = dir.resolve("nested").resolve("settings.json");
        SettingsStore store = new SettingsStore(file);
        HarnessSettings s = store.copy();
        s.model.model = "llama3.2";
        s.approval.policy = ApprovalPolicy.ALL_TOOLS;
        s.agentLoop.maxIterations = 999;
        store.replace(s);

        assertTrue(Files.isRegularFile(file));
        HarnessSettings reloaded = new SettingsStore(file).current();
        assertEquals("llama3.2", reloaded.model.model);
        assertEquals(ApprovalPolicy.ALL_TOOLS, reloaded.approval.policy);
        assertEquals(AgentLoopLimits.HARD_MAX_ITERATIONS, reloaded.agentLoop.effectiveMaxIterations());
    }

    @Test
    void corruptFileFallsBackToDefaults(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("settings.json");
        Files.writeString(file, "{not json");
        assertEquals(new HarnessSettings().model.model, new SettingsStore(file).current().model.model);
    }

    @Test
    void guiceWiringResolves(@TempDir Path dir) {
        var injector = Guice.createInjector(new AppModule(dir.resolve("s.json")));
        assertNotNull(injector.getInstance(AgentLoop.class));
        assertEquals(8, injector.getInstance(ContextBuilder.class).contributors().size());
    }
}
