package net.talaatharb.harness.benchmark;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import net.talaatharb.harness.config.HarnessSettings;
import net.talaatharb.harness.config.SettingsStore;
import net.talaatharb.harness.guardrails.SecretRedactionGuardrail;

/** Opt-in real-model benchmark; deliberately separate from Maven's deterministic tests and JavaFX startup. */
public final class BenchmarkMain {
    private BenchmarkMain() {
    }

    public static void main(String[] args) {
        try {
            int code = execute(args);
            if (code != 0) System.exit(code);
        } catch (Exception e) {
            System.err.println("Benchmark failed: " + SecretRedactionGuardrail.redact(e.getMessage()));
            System.exit(2);
        }
    }

    public static int execute(String[] args) throws IOException {
        return execute(args, new BenchmarkRunner());
    }

    static int execute(String[] args, BenchmarkRunner runner) throws IOException {
        Map<String, String> options = parse(args);
        if (options.containsKey("--help")) {
            System.out.println("--benchmark [SUITE_NAME] --model NAME [--provider ollama|openai|anthropic] [--base-url URL] "
                    + "[--settings FILE] [--suite FILE] [--output FILE] [--repeat 1..20] "
                    + "[--temperature 0..2] [--seed INTEGER]\n"
                    + "SUITE_NAME resolves to benchmark\\NAME-suite.json (default: conversational). "
                    + "--suite FILE selects any suite file.\nReports default to benchmark\\MODEL-TIMESTAMP.json; "
                    + "bare --output filenames also go in benchmark. Temporary storage is deleted afterwards.");
            return 0;
        }
        ObjectMapper mapper = new SettingsStore(null).mapper();
        HarnessSettings baseline = new HarnessSettings();
        if (options.containsKey("--settings")) {
            JsonNode root = mapper.readTree(Path.of(options.get("--settings")).toFile());
            baseline = mapper.treeToValue(root.has("settings") ? root.get("settings") : root, HarnessSettings.class);
            if (root.has("settings") && root.has("skills")) {
                baseline.skills = mapper.convertValue(root.get("skills"), mapper.getTypeFactory()
                        .constructCollectionType(ArrayList.class, net.talaatharb.harness.skills.SkillDefinition.class));
            }
        }
        if (options.containsKey("--model")) baseline.model.model = options.get("--model");
        if (options.containsKey("--provider")) {
            baseline.model.provider = options.get("--provider");
            if (!options.containsKey("--base-url")) {
                baseline.model.baseUrl = switch (baseline.model.provider) {
                    case "ollama" -> "http://localhost:11434";
                    case "openai" -> "https://api.openai.com/v1";
                    case "anthropic" -> "https://api.anthropic.com";
                    default -> throw new IllegalArgumentException("Unknown provider");
                };
            }
        }
        if (options.containsKey("--base-url")) baseline.model.baseUrl = options.get("--base-url");
        if (options.containsKey("--temperature")) {
            baseline.model.temperature = Double.valueOf(options.get("--temperature"));
            if (!Double.isFinite(baseline.model.temperature) || baseline.model.temperature < 0
                    || baseline.model.temperature > 2) throw new IllegalArgumentException("Invalid temperature");
        }
        if (options.containsKey("--seed")) baseline.model.seed = Long.valueOf(options.get("--seed"));
        int repeat = Integer.parseInt(options.getOrDefault("--repeat", "1"));
        if (repeat < 1 || repeat > 20) throw new IllegalArgumentException("Repeat must be 1..20");
        Path suitePath = suitePath(options);
        BenchmarkSuite suite = mapper.readValue(suitePath.toFile(), BenchmarkSuite.class);
        if (suite.cases.isEmpty()) throw new IllegalArgumentException("Suite is empty");
        Set<String> ids = new java.util.HashSet<>();
        for (BenchmarkSuite.Case task : suite.cases) {
            BenchmarkRunner.validate(task);
            if (!ids.add(task.id)) throw new IllegalArgumentException("Duplicate case id: " + task.id);
        }
        Path output = outputPath(options, baseline.model.model);
        if (Files.exists(output)) throw new IllegalArgumentException("Output already exists: " + output);
        Files.createDirectories(output.getParent());
        Path workspace = Files.createTempDirectory("harness-benchmark-");
        String startedAt = Instant.now().toString();
        List<BenchmarkRunner.CaseResult> results = new ArrayList<>();
        try {
            int number = 0;
            for (int run = 0; run < repeat; run++) {
                for (BenchmarkSuite.Case task : suite.cases) {
                    BenchmarkRunner.CaseResult result = runner.run(task, baseline, workspace.resolve("case-" + number++));
                    results.add(result);
                    System.out.println(SecretRedactionGuardrail.redact(task.id) + " run " + (run + 1) + ": "
                            + (result.passed() ? "checks passed" : "checks FAILED"));
                }
            }
            long checked = results.stream().flatMap(c -> c.turns().stream()).count();
            long passed = results.stream().flatMap(c -> c.turns().stream()).filter(t -> t.failures().isEmpty()).count();
            long review = results.stream().flatMap(c -> c.turns().stream())
                    .filter(t -> t.review() != null && !t.review().isBlank()).count();
            String json = mapper.writeValueAsString(Map.of("startedAt", startedAt,
                    "suite", suitePath.toString(), "repeat", repeat, "checkedTurns", checked, "checksPassed", passed,
                    "manualReviewTurns", review, "results", results));
            Files.writeString(output, SecretRedactionGuardrail.redact(json),
                    java.nio.file.StandardOpenOption.CREATE_NEW);
            System.out.println(passed + "/" + checked + " turns passed automated checks; " + review
                    + " need manual quality review. Report: " + output);
            return passed == checked ? 0 : 1;
        } finally {
            deleteWorkspace(workspace);
        }
    }

    static Map<String, String> parse(String[] args) {
        Set<String> flags = Set.of("--model", "--provider", "--base-url", "--settings", "--suite", "--output",
                "--repeat", "--temperature", "--seed");
        Map<String, String> options = new HashMap<>();
        for (int i = 0; i < args.length; i++) {
            String name = args[i];
            if (i == 0 && !name.startsWith("--")) {
                if (!name.matches("[A-Za-z0-9][A-Za-z0-9_-]*")) {
                    throw new IllegalArgumentException("Use a simple suite name, or --suite FILE for a path");
                }
                options.put("benchmark", name);
            } else if ("--help".equals(name)) {
                options.put(name, "");
            } else {
                if (!flags.contains(name) || i + 1 >= args.length || args[i + 1].startsWith("--")) {
                    throw new IllegalArgumentException("Unknown option or missing value: " + name);
                }
                if (options.put(name, args[++i]) != null) {
                    throw new IllegalArgumentException("Duplicate option: " + name);
                }
            }
        }
        if (options.containsKey("benchmark") && options.containsKey("--suite")) {
            throw new IllegalArgumentException("Choose a benchmark name or --suite FILE, not both");
        }
        return options;
    }

    static Path suitePath(Map<String, String> options) {
        return options.containsKey("--suite") ? Path.of(options.get("--suite"))
                : Path.of("benchmark", options.getOrDefault("benchmark", "conversational") + "-suite.json");
    }

    static Path outputPath(Map<String, String> options, String model) {
        String timestamp = java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS")
                .format(java.time.LocalDateTime.now());
        Path output = Path.of(options.getOrDefault("--output",
                model.replaceAll("[^A-Za-z0-9._-]", "-") + "-" + timestamp + ".json"));
        if (output.getParent() == null) output = Path.of("benchmark").resolve(output);
        return output.toAbsolutePath().normalize();
    }

    private static void deleteWorkspace(Path workspace) throws IOException {
        try (var paths = Files.walk(workspace)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(path);
            }
        }
    }
}
