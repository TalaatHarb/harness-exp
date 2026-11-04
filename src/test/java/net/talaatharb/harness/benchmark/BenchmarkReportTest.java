package net.talaatharb.harness.benchmark;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BenchmarkReportTest {
    @TempDir Path temp;

    @Test void readsExistingRunWithoutSurfacingSettings() throws Exception {
        BenchmarkReport report = BenchmarkReport.read(Path.of("benchmark", "qwen2.5-3b-run2.json"));
        assertEquals("qwen2.5:3b", report.model());
        assertEquals(34, report.turns().size());
        assertTrue(report.conclusion().startsWith("30/34"));
        assertTrue(report.conclusion().contains("24 require manual review"));
        assertTrue(report.turns().get(0).prompt().contains("hash map"));
        assertFalse(report.turns().get(0).response().isBlank());
        assertTrue(report.turns().get(0).assessment().contains("Manual review required"));
        assertTrue(report.turns().stream().anyMatch(t -> t.assessment().contains("FAILED")));
    }

    @Test void excludesSuitesSettingsAndMalformedReports() throws Exception {
        Path file = temp.resolve("input.json");
        Files.writeString(file, "{\"cases\":[]}");
        assertThrows(IllegalArgumentException.class, () -> BenchmarkReport.read(file));
        Files.writeString(file, "{\"results\":[{\"turns\":[{}]}]}");
        assertThrows(java.io.IOException.class, () -> BenchmarkReport.read(file));
    }

    @Test void sanitizesTextAndDerivesConclusionFromActualTurns() throws Exception {
        Path file = temp.resolve("report.json");
        Files.writeString(file, """
                {"checksPassed":999,"results":[{"id":"test","effectiveSettings":{"model":{"model":"demo"}},
                "turns":[{"prompt":"sk-test1234567890abcdef1234567890abcdef","reply":"Answer",
                "status":"ERROR","failures":["wrong"],"review":null}]}]}
                """);
        BenchmarkReport report = BenchmarkReport.read(file);
        assertEquals("[REDACTED]", report.turns().get(0).prompt());
        assertTrue(report.conclusion().startsWith("0/1"));
        assertTrue(report.turns().get(0).assessment().contains("Run status: ERROR"));
    }

    @Test void cliResolvesNamedSuitesAndReportFolder() {
        var named = BenchmarkMain.parse(new String[]{"travel", "--model", "demo"});
        assertEquals(Path.of("benchmark", "travel-suite.json"), BenchmarkMain.suitePath(named));
        assertEquals(Path.of("benchmark", "conversational-suite.json"), BenchmarkMain.suitePath(Map.of()));
        assertEquals(Path.of("benchmark", "run.json").toAbsolutePath(),
                BenchmarkMain.outputPath(Map.of("--output", "run.json"), "demo"));
        assertEquals(temp.resolve("run.json"),
                BenchmarkMain.outputPath(Map.of("--output", temp.resolve("run.json").toString()), "demo"));
        assertTrue(BenchmarkMain.outputPath(Map.of(), "qwen2.5:3b").getFileName().toString()
                .startsWith("qwen2.5-3b-"));
        assertThrows(IllegalArgumentException.class,
                () -> BenchmarkMain.parse(new String[]{"travel", "--suite", "another.json"}));
        assertThrows(IllegalArgumentException.class,
                () -> BenchmarkMain.parse(new String[]{"..\\outside"}));
    }
}
