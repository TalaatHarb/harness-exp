package net.talaatharb.harness.tools.builtin;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.io.BufferedReader;

import net.talaatharb.harness.tools.RiskLevel;
import net.talaatharb.harness.tools.Tool;
import net.talaatharb.harness.tools.ToolArguments;
import net.talaatharb.harness.tools.ToolResult;

/** Reads a local text file. Marked sensitive so the default approval policy asks the user first. */
public class ReadFileTool implements Tool {
    static final int MAX_BYTES = 20_000;

    @Override
    public String name() {
        return "read_file";
    }

    @Override
    public String description() {
        return "Reads up to 20KB of a local UTF-8 text file. Optionally select inclusive 1-based line ranges "
                + "with startLine and endLine.";
    }

    @Override
    public String argumentsHint() {
        return "{\"path\": \"absolute or relative file path\", \"startLine\": 1, \"endLine\": 100}";
    }

    @Override
    public RiskLevel risk() {
        return RiskLevel.SENSITIVE;
    }

    @Override
    public ToolResult execute(Map<String, Object> arguments) throws IOException {
        Object raw = arguments == null ? null : arguments.get("path");
        if (!(raw instanceof String) || ((String) raw).isBlank()) {
            return ToolResult.error("Missing required argument 'path'");
        }
        Path path;
        try {
            path = Path.of((String) raw).toAbsolutePath().normalize();
        } catch (java.nio.file.InvalidPathException e) {
            return ToolResult.error("Invalid file path");
        }
        if (!Files.isRegularFile(path)) {
            return ToolResult.error("Not a file: " + path);
        }
        if (!ToolArguments.omitted(arguments.get("startLine")) || !ToolArguments.omitted(arguments.get("endLine"))) {
            int start;
            int end;
            try {
                start = ToolArguments.integer(arguments, "startLine", 1, Integer.MAX_VALUE);
                end = ToolArguments.integer(arguments, "endLine", Integer.MAX_VALUE, Integer.MAX_VALUE);
            } catch (IllegalArgumentException e) {
                return ToolResult.error(e.getMessage());
            }
            if (end < start) {
                return ToolResult.error("'endLine' must not be less than 'startLine'");
            }
            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
                int number = 1;
                int scanned = 0;
                boolean atLineStart = true;
                int ch;
                while (number <= end && (ch = reader.read()) != -1) {
                    if (++scanned > 8_000_000) {
                        output.append("[truncated; scan limit reached]");
                        break;
                    }
                    if (number >= start && atLineStart) {
                        output.append(number).append(": ");
                        atLineStart = false;
                    }
                    if (ch == '\r' || ch == '\n') {
                        boolean crlf = false;
                        if (ch == '\r') {
                            reader.mark(1);
                            if (reader.read() == '\n') {
                                crlf = true;
                            } else {
                                reader.reset();
                            }
                        }
                        if (number >= start) {
                            output.append((char) ch);
                            if (crlf) {
                                output.append('\n');
                            }
                        }
                        number++;
                        atLineStart = true;
                    } else if (number >= start) {
                        output.append((char) ch);
                    }
                    if (output.length() >= MAX_BYTES) {
                        output.append("\n[truncated; narrow the line range]");
                        break;
                    }
                }
            }
            return ToolResult.ok(output.toString());
        }
        try (InputStream in = Files.newInputStream(path)) {
            byte[] bytes = in.readNBytes(MAX_BYTES);
            String text = new String(bytes, StandardCharsets.UTF_8);
            boolean truncated = Files.size(path) > MAX_BYTES;
            return ToolResult.ok(truncated ? text + "\n[truncated]" : text);
        }
    }
}
