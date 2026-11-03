package net.talaatharb.harness.tools.mcp;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.node.ObjectNode;

/** MCP over stdio: the server is a child process exchanging newline-delimited JSON-RPC on stdin/stdout. */
public class StdioMcpTransport extends McpTransport {
    private static final Logger LOG = LoggerFactory.getLogger(StdioMcpTransport.class);
    private static final int STDERR_LINES_KEPT = 20;

    private final String serverName;
    private final Process process;
    private final OutputStream stdin;
    private final Deque<String> stderrTail = new ArrayDeque<>();

    public StdioMcpTransport(String serverName, String commandLine, Map<String, String> env) throws IOException {
        this.serverName = serverName;
        List<String> command = command(commandLine, isWindows());
        if (command.isEmpty()) {
            throw new IOException("No command configured for MCP server '" + serverName + "'");
        }
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.environment().putAll(env);
        try {
            process = builder.start();
        } catch (IOException e) {
            throw new IOException("Could not start '" + command.get(0) + "': " + e.getMessage(), e);
        }
        LOG.info("Started MCP server '{}' (pid {}): {}", serverName, process.pid(), command);
        stdin = process.getOutputStream();
        Thread reader = new Thread(this::readStdout, "mcp-" + serverName + "-out");
        reader.setDaemon(true);
        reader.start();
        Thread errors = new Thread(this::readStderr, "mcp-" + serverName + "-err");
        errors.setDaemon(true);
        errors.start();
    }

    /**
     * On Windows, {@code npx}, {@code uvx} etc. are .cmd/.bat shims that {@link ProcessBuilder} can't start directly,
     * so anything that isn't an .exe runs through {@code cmd /c}.
     */
    static List<String> command(String commandLine, boolean windows) {
        List<String> args = McpServerConfig.tokenize(commandLine == null ? "" : commandLine.strip());
        if (args.isEmpty() || !windows) {
            return args;
        }
        String program = args.get(0).toLowerCase(Locale.ROOT);
        if (program.endsWith(".exe") || program.equals("cmd") || program.equals("cmd.exe")) {
            return args;
        }
        List<String> wrapped = new ArrayList<>(List.of("cmd.exe", "/c"));
        wrapped.addAll(args);
        return wrapped;
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    @Override
    protected synchronized void send(ObjectNode message) throws IOException {
        if (!process.isAlive()) {
            throw new McpException("MCP server '" + serverName + "' is not running" + stderrSuffix());
        }
        stdin.write((JSON.writeValueAsString(message) + "\n").getBytes(StandardCharsets.UTF_8));
        stdin.flush();
    }

    private void readStdout() {
        try (BufferedReader in = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = in.readLine()) != null) {
                String trimmed = line.strip();
                if (trimmed.isEmpty()) {
                    continue;
                }
                if (!trimmed.startsWith("{")) {
                    LOG.debug("[{}] non-JSON output: {}", serverName, trimmed);
                    continue;
                }
                try {
                    receive(JSON.readTree(trimmed));
                } catch (IOException e) {
                    LOG.warn("[{}] invalid JSON from server: {}", serverName, trimmed);
                }
            }
        } catch (IOException e) {
            LOG.debug("[{}] stdout closed", serverName, e);
        }
        try {
            process.waitFor(2, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        String reason = process.isAlive() ? "stdout closed" : "process exited with code " + process.exitValue();
        if (isOpen()) {
            LOG.warn("MCP server '{}' stopped: {}{}", serverName, reason, stderrSuffix());
        }
        closed(reason + stderrSuffix());
    }

    private void readStderr() {
        try (BufferedReader in = new BufferedReader(
                new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = in.readLine()) != null) {
                LOG.debug("[{}] {}", serverName, line);
                synchronized (stderrTail) {
                    stderrTail.addLast(line);
                    if (stderrTail.size() > STDERR_LINES_KEPT) {
                        stderrTail.removeFirst();
                    }
                }
            }
        } catch (IOException e) {
            LOG.debug("[{}] stderr closed", serverName, e);
        }
    }

    private String stderrSuffix() {
        synchronized (stderrTail) {
            if (stderrTail.isEmpty()) {
                return "";
            }
            List<String> last = new ArrayList<>(stderrTail);
            return " — stderr: " + String.join(" | ", last.subList(Math.max(0, last.size() - 3), last.size()));
        }
    }

    @Override
    public void close() {
        List<ProcessHandle> children = process.descendants().toList();
        closed("closed by the harness");
        try {
            stdin.close();
        } catch (IOException e) {
            LOG.debug("[{}] closing stdin", serverName, e);
        }
        try {
            if (!process.waitFor(2, TimeUnit.SECONDS)) {
                process.destroy();
                if (!process.waitFor(3, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                }
            }
            children.stream().filter(ProcessHandle::isAlive).forEach(ProcessHandle::destroyForcibly);
        } catch (InterruptedException e) {
            children.forEach(ProcessHandle::destroyForcibly);
            process.destroyForcibly();
            Thread.currentThread().interrupt();
        }
        LOG.info("Stopped MCP server '{}'", serverName);
    }
}
