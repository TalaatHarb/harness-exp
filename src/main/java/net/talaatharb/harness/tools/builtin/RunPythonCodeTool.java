package net.talaatharb.harness.tools.builtin;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import net.talaatharb.harness.tools.RiskLevel;
import net.talaatharb.harness.tools.Tool;
import net.talaatharb.harness.tools.ToolResult;

/** Runs Python source with the system interpreter. Dangerous: every call needs approval. Not sandboxed. */
public class RunPythonCodeTool implements Tool {
    static final int MAX_CODE_CHARS = 200_000;

    @Override
    public String name() {
        return "run_python_code";
    }

    @Override
    public String description() {
        return "Runs Python 3 code with the system interpreter (python3, python, or py) and returns the exit code and "
                + "combined output (capped at 20KB). Default timeout 60s, maximum 600s. No stdin.";
    }

    @Override
    public String argumentsHint() {
        return "{\"code\": \"print('hi')\", \"workingDirectory\": \"optional folder\", \"timeoutSeconds\": 60}";
    }

    @Override
    public RiskLevel risk() {
        return RiskLevel.DANGEROUS;
    }

    static List<String> interpreter() {
        if (ProcessRunner.onPath("python3")) {
            return List.of("python3");
        }
        if (ProcessRunner.onPath("python")) {
            return List.of("python");
        }
        if (ProcessRunner.isWindows() && ProcessRunner.onPath("py")) {
            return List.of("py", "-3");
        }
        return null;
    }

    @Override
    public ToolResult execute(Map<String, Object> arguments) throws IOException, InterruptedException {
        if (arguments == null) {
            return ToolResult.error("Missing required argument 'code'");
        }
        Object code = arguments.get("code");
        if (!(code instanceof String source) || source.isBlank()) {
            return ToolResult.error("Missing required argument 'code'");
        }
        if (source.length() > MAX_CODE_CHARS) {
            return ToolResult.error("Code is too large (max " + MAX_CODE_CHARS + " characters)");
        }
        Path workingDir;
        int timeout;
        try {
            workingDir = ProcessRunner.workingDirectory(arguments.get("workingDirectory"));
            timeout = ProcessRunner.timeoutSeconds(arguments.get("timeoutSeconds"));
        } catch (IllegalArgumentException e) {
            return ToolResult.error("Python arguments rejected: " + e.getMessage());
        }
        List<String> python = interpreter();
        if (python == null) {
            return ToolResult.error("No Python interpreter found on PATH (tried python3, python, py)");
        }
        Path script = Files.createTempFile("harness-python-", ".py");
        try {
            Files.writeString(script, source, StandardCharsets.UTF_8);
            List<String> command = new ArrayList<>(python);
            command.add("-X");
            command.add("utf8");
            command.add(script.toString());
            return ProcessRunner.run(command, workingDir, timeout);
        } finally {
            Files.deleteIfExists(script);
        }
    }
}
