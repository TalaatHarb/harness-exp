package net.talaatharb.harness.tools.builtin;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import net.talaatharb.harness.tools.RiskLevel;
import net.talaatharb.harness.tools.Tool;
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
        return "Reads up to 20KB of a local UTF-8 text file.";
    }

    @Override
    public String argumentsHint() {
        return "{\"path\": \"absolute or relative file path\"}";
    }

    @Override
    public RiskLevel risk() {
        return RiskLevel.SENSITIVE;
    }

    @Override
    public ToolResult execute(Map<String, Object> arguments) throws IOException {
        Object raw = arguments.get("path");
        if (!(raw instanceof String) || ((String) raw).isBlank()) {
            return ToolResult.error("Missing required argument 'path'");
        }
        Path path = Path.of((String) raw).toAbsolutePath().normalize();
        if (!Files.isRegularFile(path)) {
            return ToolResult.error("Not a file: " + path);
        }
        try (InputStream in = Files.newInputStream(path)) {
            byte[] bytes = in.readNBytes(MAX_BYTES);
            String text = new String(bytes, StandardCharsets.UTF_8);
            boolean truncated = Files.size(path) > MAX_BYTES;
            return ToolResult.ok(truncated ? text + "\n[truncated]" : text);
        }
    }
}
