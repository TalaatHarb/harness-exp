package net.talaatharb.harness.benchmark;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import net.talaatharb.harness.config.HarnessSettings;
import net.talaatharb.harness.config.SettingsStore;
import net.talaatharb.harness.llm.ModelProvider;
import net.talaatharb.harness.llm.ModelReply;
import net.talaatharb.harness.llm.ModelRequest;
import net.talaatharb.harness.llm.ModelSettings;

class BenchmarkRunnerTest {
    @TempDir Path temp;

    static class Fake implements ModelProvider {
        final List<ModelRequest> requests = new ArrayList<>();
        public String id() { return "fake"; }
        public String displayName() { return "Fake"; }
        public boolean ping(ModelSettings s) { return true; }
        public List<String> listModels(ModelSettings s) { return List.of("fake"); }
        public ModelReply chat(ModelSettings s, ModelRequest r, Consumer<String> tokens) {
            requests.add(r);
            return new ModelReply("YES", null, 0);
        }
    }

    @Test void executesProductionLoopWithIsolatedStorageAndRedactedContext() throws Exception {
        Fake fake = new Fake();
        HarnessSettings settings = new HarnessSettings();
        settings.model.provider = "fake";
        settings.model.apiKey = "do-not-export-this-arbitrary-key";
        Path real = temp.resolve("desktop");
        settings.storage.memoryDir = real.toString();
        settings.storage.ragIndex = real.toString();
        BenchmarkSuite.Case task = new BenchmarkSuite.Case();
        task.id = "test";
        task.settings = new SettingsStore(null).mapper().readTree(
                "{\"storage\":{\"skillsDir\":\"" + real.toString().replace("\\", "\\\\") + "\"}}");
        BenchmarkSuite.Turn turn = new BenchmarkSuite.Turn();
        turn.prompt = "Key: sk-test1234567890abcdef1234567890abcdef";
        turn.exact = "YES";
        turn.contextExcludes.add("sk-test1234567890abcdef1234567890abcdef");
        task.turns.add(turn);
        var result = new BenchmarkRunner(Set.of(fake)).run(task, settings, temp.resolve("case"));
        assertTrue(result.passed(), result.turns().toString());
        assertFalse(Files.exists(real));
        assertFalse(result.effectiveSettings().get("model").has("apiKey"));
        assertEquals(1, fake.requests.size());
    }

    @Test void preservesConversationWithinCaseButNotAcrossConversationNames() throws Exception {
        Fake fake = new Fake();
        HarnessSettings settings = new HarnessSettings();
        settings.model.provider = "fake";
        BenchmarkSuite.Case task = new BenchmarkSuite.Case();
        task.id = "context";
        for (String name : List.of("main", "main", "fresh")) {
            BenchmarkSuite.Turn turn = new BenchmarkSuite.Turn();
            turn.prompt = name.equals("fresh") ? "Fresh" : "Earlier detail";
            turn.conversation = name;
            task.turns.add(turn);
        }
        assertTrue(new BenchmarkRunner(Set.of(fake)).run(task, settings, temp.resolve("case")).passed());
        assertTrue(fake.requests.get(1).messages().stream().filter(m -> m.content().equals("Earlier detail")).count() >= 2);
        assertFalse(fake.requests.get(2).messages().stream().anyMatch(m -> m.content().equals("Earlier detail")));
    }

    @Test void validatesSuiteAndCliBeforeExecution() throws Exception {
        BenchmarkSuite suite = new SettingsStore(null).mapper().readValue(
                Path.of("benchmark", "conversational-suite.json").toFile(), BenchmarkSuite.class);
        suite.cases.forEach(BenchmarkRunner::validate);
        BenchmarkSuite.Case bad = new BenchmarkSuite.Case();
        assertThrows(IllegalArgumentException.class, () -> BenchmarkRunner.validate(bad));
        assertThrows(IllegalArgumentException.class, () -> BenchmarkMain.parse(new String[]{"--model"}));
        assertThrows(IllegalArgumentException.class, () -> BenchmarkMain.parse(new String[]{"--bogus", "x"}));
    }

    @Test void consoleWritesSanitizedReportAndRefusesOverwrite() throws Exception {
        var mapper = new SettingsStore(null).mapper();
        HarnessSettings settings = new HarnessSettings();
        settings.model.provider = "fake";
        settings.model.apiKey = "arbitrary-do-not-write-to-report";
        Path config = temp.resolve("input.json");
        mapper.writeValue(config.toFile(), settings);
        Path suite = temp.resolve("suite.json");
        Files.writeString(suite, """
                {"cases":[{"id":"smoke","turns":[{"prompt":"Reply YES","exact":"YES"}]}]}
                """);
        Path report = temp.resolve("report.json");
        String[] args = {"--settings", config.toString(), "--suite", suite.toString(),
                "--output", report.toString()};
        assertEquals(0, BenchmarkMain.execute(args, new BenchmarkRunner(Set.of(new Fake()))));
        String json = Files.readString(report);
        assertFalse(json.contains("arbitrary-do-not-write-to-report"));
        assertEquals(1, mapper.readTree(json).get("checksPassed").asInt());
        assertThrows(IllegalArgumentException.class,
                () -> BenchmarkMain.execute(args, new BenchmarkRunner(Set.of(new Fake()))));
    }

    @Test void explicitMemoryRequestsSaveAllFactsWithoutRelyingOnModelExtraction() throws Exception {
        Fake fake = new Fake();
        HarnessSettings settings = new HarnessSettings();
        settings.model.provider = "fake";
        BenchmarkSuite.Case task = new BenchmarkSuite.Case();
        task.id = "explicit-memory";
        task.approveTools.add("remember");
        BenchmarkSuite.Turn turn = new BenchmarkSuite.Turn();
        turn.prompt = "Remember that I prefer metric units and my dog is called Pixel.";
        turn.savedMemories = List.of("metric units", "Pixel");
        turn.memoryCount = 1;
        turn.tools.add("remember");
        task.turns.add(turn);
        task.turns.add(turn);
        var result = new BenchmarkRunner(Set.of(fake)).run(task, settings, temp.resolve("direct-memory"));
        assertTrue(result.passed(), result.turns().toString());
        assertEquals(0, fake.requests.size());
    }
}
