package net.talaatharb.harness.tools.builtin;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import net.talaatharb.harness.tools.ToolResult;

/** Runs an external process with a timeout and a bounded, merged stdout/stderr capture. */
final class ProcessRunner {
    static final int DEFAULT_TIMEOUT_SECONDS = 60;
    static final int MAX_TIMEOUT_SECONDS = 600;
    static final int MAX_OUTPUT_BYTES = 20_000;

    private ProcessRunner() {
    }

    static int timeoutSeconds(Object raw) {
        if (raw == null) {
            return DEFAULT_TIMEOUT_SECONDS;
        }
        try {
            int value = (int) Math.round(Double.parseDouble(raw.toString()));
            return Math.max(1, Math.min(MAX_TIMEOUT_SECONDS, value));
        } catch (NumberFormatException e) {
            return DEFAULT_TIMEOUT_SECONDS;
        }
    }

    /** Resolves an optional working directory argument; {@code null} means the current one. */
    static Path workingDirectory(Object raw) {
        if (!(raw instanceof String s) || s.isBlank()) {
            return null;
        }
        return Path.of(s).toAbsolutePath().normalize();
    }

    static ToolResult run(List<String> command, Path workingDir, int timeoutSeconds) throws InterruptedException {
        if (workingDir != null && !Files.isDirectory(workingDir)) {
            return ToolResult.error("Working directory does not exist: " + workingDir);
        }
        ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true);
        if (workingDir != null) {
            builder.directory(workingDir.toFile());
        }
        builder.redirectInput(ProcessBuilder.Redirect.from(nullDevice()));
        Process process;
        try {
            process = builder.start();
        } catch (IOException e) {
            return ToolResult.error("Could not start " + command.get(0) + ": " + e.getMessage());
        }
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        boolean[] truncated = {false};
        Thread reader = Thread.ofVirtual().start(() -> drain(process.getInputStream(), captured, truncated));
        try {
            boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            if (!finished) {
                kill(process);
                reader.join(2_000);
                return ToolResult.error("Timed out after " + timeoutSeconds + "s and was killed.\n"
                        + text(captured, truncated[0]));
            }
            reader.join(2_000);
        } catch (InterruptedException e) {
            kill(process);
            throw e;
        }
        int exit = process.exitValue();
        String output = text(captured, truncated[0]);
        String body = "exit code " + exit + (output.isEmpty() ? "" : "\n" + output);
        return exit == 0 ? ToolResult.ok(body) : ToolResult.error(body);
    }

    private static void drain(InputStream in, ByteArrayOutputStream out, boolean[] truncated) {
        byte[] buffer = new byte[4096];
        try (in) {
            int n;
            while ((n = in.read(buffer)) >= 0) {
                synchronized (out) {
                    int room = MAX_OUTPUT_BYTES - out.size();
                    if (room > 0) {
                        out.write(buffer, 0, Math.min(room, n));
                    }
                    if (n > room) {
                        truncated[0] = true;
                    }
                }
            }
        } catch (IOException ignored) {
            // The process was killed or closed its output.
        }
    }

    private static String text(ByteArrayOutputStream out, boolean truncated) {
        synchronized (out) {
            String s = out.toString(StandardCharsets.UTF_8).strip();
            return truncated ? s + "\n[output truncated]" : s;
        }
    }

    private static void kill(Process process) {
        process.descendants().forEach(ProcessHandle::destroyForcibly);
        process.destroyForcibly();
    }

    private static java.io.File nullDevice() {
        return new java.io.File(isWindows() ? "NUL" : "/dev/null");
    }

    static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win");
    }

    static boolean onPath(String executable) {
        String path = System.getenv("PATH");
        if (path == null) {
            return false;
        }
        for (String dir : path.split(java.io.File.pathSeparator)) {
            Path candidate = Path.of(dir, executable + (isWindows() ? ".exe" : ""));
            if (Files.isExecutable(candidate)) {
                return true;
            }
        }
        return false;
    }
}
