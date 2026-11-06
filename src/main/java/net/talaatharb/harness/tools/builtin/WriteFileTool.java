package net.talaatharb.harness.tools.builtin;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Map;

import net.talaatharb.harness.tools.RiskLevel;
import net.talaatharb.harness.tools.Tool;
import net.talaatharb.harness.tools.ToolResult;

/** Writes a local UTF-8 text file. Sensitive: the default approval policy asks the user first. */
public class WriteFileTool implements Tool {
    static final int MAX_CHARS = 1_000_000;

    @Override
    public String name() {
        return "write_file";
    }

    @Override
    public String description() {
        return "Writes UTF-8 text to a local file, replacing it unless append is true. Parent folders are created. "
                + "Content is limited to 1,000,000 characters.";
    }

    @Override
    public String argumentsHint() {
        return "{\"path\": \"absolute or relative file path\", \"content\": \"text\", \"append\": false}";
    }

    @Override
    public RiskLevel risk() {
        return RiskLevel.SENSITIVE;
    }

    @Override
    public ToolResult execute(Map<String, Object> arguments) throws IOException {
        Object rawPath = arguments.get("path");
        Object content = arguments.get("content");
        if (!(rawPath instanceof String p) || p.isBlank()) {
            return ToolResult.error("Missing required argument 'path'");
        }
        if (!(content instanceof String text)) {
            return ToolResult.error("Missing required string argument 'content'");
        }
        if (text.length() > MAX_CHARS) {
            return ToolResult.error("Content is too large (" + text.length() + " > " + MAX_CHARS + " characters)");
        }
        boolean append = Boolean.parseBoolean(String.valueOf(arguments.getOrDefault("append", false)));
        Path path = Path.of(p).toAbsolutePath().normalize();
        if (Files.isDirectory(path)) {
            return ToolResult.error("Path is a directory: " + path);
        }
        if (path.getParent() != null) {
            Files.createDirectories(path.getParent());
        }
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        if (append) {
            Files.write(path, bytes, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } else {
            Files.write(path, bytes);
        }
        return ToolResult.ok((append ? "Appended " : "Wrote ") + bytes.length + " bytes to " + path);
    }
}
