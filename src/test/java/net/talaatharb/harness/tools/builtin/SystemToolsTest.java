package net.talaatharb.harness.tools.builtin;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.io.TempDir;

import net.talaatharb.harness.interaction.DecisionRequest;
import net.talaatharb.harness.interaction.DecisionResponse;
import net.talaatharb.harness.tools.RiskLevel;
import net.talaatharb.harness.tools.ToolResult;

class SystemToolsTest {
    @TempDir Path temp;

    @Test
    void writeFileWritesAppendsAndCreatesFolders() throws Exception {
        WriteFileTool tool = new WriteFileTool();
        assertEquals(RiskLevel.SENSITIVE, tool.risk());
        Path file = temp.resolve("a/b/out.txt");
        assertTrue(tool.execute(Map.of("path", file.toString(), "content", "héllo")).success());
        assertTrue(tool.execute(Map.of("path", file.toString(), "content", "!", "append", true)).success());
        assertEquals("héllo!", Files.readString(file));
        assertTrue(tool.execute(Map.of("path", file.toString(), "content", "new")).success());
        assertEquals("new", Files.readString(file));
        assertFalse(tool.execute(Map.of("path", temp.toString(), "content", "x")).success());
        assertFalse(tool.execute(Map.of("content", "x")).success());
        assertFalse(tool.execute(Map.of("path", file.toString())).success());
    }

    @Test
    void shellAndPythonAreDangerousAndRun() throws Exception {
        RunNativeShellTool shell = new RunNativeShellTool();
        RunPythonCodeTool python = new RunPythonCodeTool();
        assertEquals(RiskLevel.DANGEROUS, shell.risk());
        assertEquals(RiskLevel.DANGEROUS, python.risk());

        ToolResult echo = shell.execute(Map.of("command", "echo hello", "workingDirectory", temp.toString()));
        assertTrue(echo.success(), echo.output());
        assertTrue(echo.output().contains("hello"));

        ToolResult failure = shell.execute(Map.of("command", ProcessRunner.isWindows() ? "exit 3" : "echo oops >&2; exit 3"));
        assertFalse(failure.success());
        assertTrue(failure.output().contains("exit code 3"));

        Assumptions.assumeTrue(RunPythonCodeTool.interpreter() != null, "No Python available");
        ToolResult py = python.execute(Map.of("code", "print(6*7)"));
        assertTrue(py.success(), py.output());
        assertTrue(py.output().contains("42"));
        assertFalse(python.execute(Map.of("code", "raise SystemExit(2)")).success());
    }

    @Test
    void longRunningCommandsAreKilledAtTheTimeout() throws Exception {
        ToolResult result = new RunNativeShellTool().execute(Map.of("command",
                ProcessRunner.isWindows() ? "Start-Sleep 30" : "sleep 30", "timeoutSeconds", 1));
        assertFalse(result.success());
        assertTrue(result.output().contains("Timed out"));
    }

    @Test
    void outputIsCapped() throws Exception {
        ToolResult result = new RunNativeShellTool().execute(Map.of("command", ProcessRunner.isWindows()
                ? "Write-Output ('x' * 100000)" : "head -c 100000 /dev/zero | tr '\\0' x"));
        assertTrue(result.success(), result.output());
        assertTrue(result.output().contains("[output truncated]"));
        assertTrue(result.output().length() < 21_000);
    }

    @Test
    void shellPreservesUnicodeAndReportsPowerShellErrors() throws Exception {
        String expected = "h\u00e9llo \u4e16\u754c";
        RunNativeShellTool shell = new RunNativeShellTool();
        ToolResult unicode = shell.execute(Map.of("command", ProcessRunner.isWindows()
                ? "Write-Output '" + expected + "'" : "printf '" + expected + "'"));
        assertTrue(unicode.success(), unicode.output());
        assertTrue(unicode.output().contains(expected), unicode.output());
        if (ProcessRunner.isWindows()) {
            ToolResult error = shell.execute(Map.of("command", "Write-Error 'failure'; Write-Output 'not reached'"));
            assertFalse(error.success(), error.output());
            assertFalse(error.output().contains("not reached"), error.output());
        }
    }

    @Test
    void shellUsesTheRequestedWorkingDirectory() throws Exception {
        Files.writeString(temp.resolve("input.txt"), "working directory verified");
        ToolResult result = new RunNativeShellTool().execute(Map.of("command",
                ProcessRunner.isWindows() ? "Get-Content input.txt" : "cat input.txt",
                "workingDirectory", temp.toString()));
        assertTrue(result.success(), result.output());
        assertTrue(result.output().contains("working directory verified"), result.output());
    }

    @Test
    void executionArgumentsAreValidated() throws Exception {
        RunNativeShellTool shell = new RunNativeShellTool();
        RunPythonCodeTool python = new RunPythonCodeTool();
        assertFalse(shell.execute(null).success());
        assertFalse(python.execute(null).success());
        for (Object timeout : List.of("oops", "NaN", "Infinity")) {
            assertFalse(shell.execute(Map.of("command", "echo hi", "timeoutSeconds", timeout)).success());
            assertFalse(python.execute(Map.of("code", "print(42)", "timeoutSeconds", timeout)).success());
        }
        assertFalse(shell.execute(Map.of("command", "echo hi", "workingDirectory", 42)).success());
        assertFalse(python.execute(Map.of("code", "print(42)", "workingDirectory", 42)).success());
        assertFalse(shell.execute(Map.of("command", "echo hi",
                "workingDirectory", temp.resolve("missing").toString())).success());
        assertEquals(60, ProcessRunner.timeoutSeconds(null));
        assertEquals(600, ProcessRunner.timeoutSeconds("1e20"));
    }

    @Test
    void pythonPreservesUnicodeWorkingDirectoryAndStderrAndEnforcesBounds() throws Exception {
        Assumptions.assumeTrue(RunPythonCodeTool.interpreter() != null, "No Python available");
        RunPythonCodeTool python = new RunPythonCodeTool();
        String expected = "h\u00e9llo \u4e16\u754c";
        Files.writeString(temp.resolve("input.txt"), expected);
        ToolResult result = python.execute(Map.of("code",
                "from pathlib import Path\nimport sys\n"
                + "print(Path('input.txt').read_text(encoding='utf-8'))\n"
                + "print('stderr test', file=sys.stderr)\nprint(input())", "workingDirectory", temp.toString()));
        assertFalse(result.success(), "stdin is closed and input() must fail");
        assertTrue(result.output().contains(expected), result.output());
        assertTrue(result.output().contains("stderr test"), result.output());
        assertTrue(result.output().contains("EOFError"), result.output());

        ToolResult timeout = python.execute(Map.of("code",
                "import time\nprint('partial', flush=True)\ntime.sleep(30)", "timeoutSeconds", 1));
        assertFalse(timeout.success());
        assertTrue(timeout.output().contains("Timed out"), timeout.output());
        assertTrue(timeout.output().contains("partial"), timeout.output());
        ToolResult capped = python.execute(Map.of("code", "print('x'*100000)"));
        assertTrue(capped.success(), capped.output());
        assertTrue(capped.output().contains("[output truncated]"));
        assertTrue(capped.output().length() < 21_000);
    }

    @Test
    void askUserReturnsChoiceAndInputAndReportsDismissal() throws Exception {
        DecisionRequest[] seen = new DecisionRequest[1];
        AskUserForDecisionTool tool = new AskUserForDecisionTool(r -> {
            seen[0] = r;
            return CompletableFuture.completedFuture(new DecisionResponse("B", "because"));
        });
        ToolResult result = tool.execute(Map.of("question", "Pick?", "options", List.of("A", "B", "B"),
                "inputLabel", "Reason"));
        assertTrue(result.success());
        assertEquals("Chosen option: B\nReason: because", result.output());
        assertEquals(List.of("A", "B"), seen[0].options());
        assertEquals(DecisionRequest.Mode.CHOICES, seen[0].mode());

        assertFalse(tool.execute(Map.of("options", List.of("A"))).success());
        assertFalse(new AskUserForDecisionTool(r -> CompletableFuture.completedFuture(null))
                .execute(Map.of("question", "Q")).success());
    }

    @Test
    void decisionModeDependsOnOptionsAndInput() {
        assertEquals(DecisionRequest.Mode.BINARY, new DecisionRequest("q", List.of("Yes", "No"), null).mode());
        assertEquals(DecisionRequest.Mode.CHOICES, new DecisionRequest("q", List.of("Yes", "No"), "Why").mode());
        assertEquals(DecisionRequest.Mode.CHOICES, new DecisionRequest("q", List.of("a", "b", "c"), null).mode());
        assertEquals(DecisionRequest.Mode.CHOICES, new DecisionRequest("q", List.of("a"), null).mode());
        assertEquals(DecisionRequest.Mode.INPUT_ONLY, new DecisionRequest("q", List.of(), "Name").mode());
    }
}
