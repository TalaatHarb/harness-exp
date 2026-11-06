package net.talaatharb.harness.tools.builtin;

import java.util.List;
import java.util.Map;

import net.talaatharb.harness.tools.RiskLevel;
import net.talaatharb.harness.tools.Tool;
import net.talaatharb.harness.tools.ToolResult;

/**
 * Runs a command in the operating system's native shell: PowerShell on Windows, otherwise bash (or sh). Dangerous:
 * every call needs human approval. Not sandboxed.
 */
public class RunNativeShellTool implements Tool {

    @Override
    public String name() {
        return "run_native_shell";
    }

    @Override
    public String description() {
        return "Runs a command in the native OS shell (PowerShell on Windows, bash/sh elsewhere) and returns the exit "
                + "code and combined output (capped at 20KB). Default timeout 60s, maximum 600s. No stdin.";
    }

    @Override
    public String argumentsHint() {
        return "{\"command\": \"shell command\", \"workingDirectory\": \"optional folder\", \"timeoutSeconds\": 60}";
    }

    @Override
    public RiskLevel risk() {
        return RiskLevel.DANGEROUS;
    }

    static List<String> shellCommand(String command) {
        if (ProcessRunner.isWindows()) {
            String shell = ProcessRunner.onPath("pwsh") ? "pwsh" : "powershell";
            return List.of(shell, "-NoProfile", "-NonInteractive", "-Command", command);
        }
        return List.of(ProcessRunner.onPath("bash") ? "bash" : "sh", "-c", command);
    }

    @Override
    public ToolResult execute(Map<String, Object> arguments) throws InterruptedException {
        Object command = arguments.get("command");
        if (!(command instanceof String c) || c.isBlank()) {
            return ToolResult.error("Missing required argument 'command'");
        }
        return ProcessRunner.run(shellCommand(c), ProcessRunner.workingDirectory(arguments.get("workingDirectory")),
                ProcessRunner.timeoutSeconds(arguments.get("timeoutSeconds")));
    }
}
