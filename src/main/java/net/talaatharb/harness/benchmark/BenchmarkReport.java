package net.talaatharb.harness.benchmark;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import net.talaatharb.harness.guardrails.SecretRedactionGuardrail;

/** Read-only projection of reports; settings and suites in the same folder are not reports. */
public record BenchmarkReport(Path file, String model, String suite, String date, List<Turn> turns) {
    public record Turn(String caseId, String model, String prompt, String response, String status,
            List<String> failures, String review) {
        public String assessment() {
            String result = failures.isEmpty() ? "Automated checks passed"
                    : "Automated checks FAILED:\n" + String.join("\n", failures);
            if (!"COMPLETED".equals(status)) result += "\nRun status: " + status;
            if (!review.isBlank()) result += "\nManual review required: " + review;
            return result;
        }
    }

    public static BenchmarkReport read(Path file) throws IOException {
        if (Files.size(file) > 20 * 1024 * 1024) throw new IOException("Report exceeds 20 MB: " + file.getFileName());
        JsonNode root = new ObjectMapper().readTree(file.toFile());
        if (root == null || !root.path("results").isArray()) {
            throw new IllegalArgumentException("Not a benchmark run report");
        }
        List<Turn> turns = new ArrayList<>();
        LinkedHashSet<String> models = new LinkedHashSet<>();
        for (JsonNode result : root.get("results")) {
            String model = safe(result.path("effectiveSettings").path("model").path("model").asText("Unknown model"));
            models.add(model);
            if (!result.path("turns").isArray()) throw new IOException("Case has no turns: " + file.getFileName());
            for (JsonNode turn : result.get("turns")) {
                if (!turn.path("prompt").isTextual() || !turn.path("failures").isArray()) {
                    throw new IOException("Invalid turn in " + file.getFileName());
                }
                List<String> failures = new ArrayList<>();
                turn.get("failures").forEach(f -> failures.add(safe(f.asText())));
                turns.add(new Turn(safe(result.path("id").asText()), model, safe(turn.path("prompt").asText()),
                        safe(turn.path("reply").asText("")), safe(turn.path("status").asText("Unknown")),
                        List.copyOf(failures), safe(turn.path("review").asText(""))));
            }
        }
        return new BenchmarkReport(file.toAbsolutePath(), String.join(", ", models),
                safe(root.path("suite").asText("Unknown suite")), safe(root.path("startedAt").asText("")),
                List.copyOf(turns));
    }

    public String conclusion() {
        long passed = turns.stream().filter(t -> t.failures().isEmpty()).count();
        long review = turns.stream().filter(t -> !t.review().isBlank()).count();
        return passed + "/" + turns.size() + " turns passed automated checks; " + review
                + " require manual review. Not a conversational quality score.";
    }

    private static String safe(String value) {
        return SecretRedactionGuardrail.redact(value);
    }
}
