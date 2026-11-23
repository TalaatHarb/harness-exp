package net.talaatharb.harness.tools.builtin;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import net.talaatharb.harness.tools.RiskLevel;
import net.talaatharb.harness.tools.Tool;
import net.talaatharb.harness.tools.ToolResult;

/** Replaces one unambiguous exact match without rewriting unrelated text. */
public class EditFileTool implements Tool {
    private static final int MAX_BYTES = 1_000_000;

    @Override
    public String name() {
        return "edit_file";
    }

    @Override
    public String description() {
        return "Replaces exactly one occurrence of oldText with newText in an existing UTF-8 file. "
                + "Read the file first and include enough context to make oldText unique. "
                + "Whitespace and line endings must match exactly; an empty newText deletes the match. "
                + "Files and results are limited to 1,000,000 bytes.";
    }

    @Override
    public String argumentsHint() {
        return "{\"path\":\"file path\", \"oldText\":\"exact unique text\", \"newText\":\"replacement\"}";
    }

    @Override
    public RiskLevel risk() {
        return RiskLevel.SENSITIVE;
    }

    @Override
    public ToolResult execute(Map<String, Object> arguments) throws IOException {
        if (arguments == null || !(arguments.get("path") instanceof String p) || p.isBlank()) {
            return ToolResult.error("Missing required argument 'path'");
        }
        if (!(arguments.get("oldText") instanceof String oldText) || oldText.isEmpty()) {
            return ToolResult.error("'oldText' must be a non-empty string");
        }
        if (!(arguments.get("newText") instanceof String newText)) {
            return ToolResult.error("'newText' must be a string");
        }
        if (newText.indexOf('\0') >= 0 || !StandardCharsets.UTF_8.newEncoder().canEncode(newText)) {
            return ToolResult.error("'newText' must be valid UTF-8 text without NUL characters");
        }
        Path path;
        try {
            path = Path.of(p).toAbsolutePath().normalize();
        } catch (java.nio.file.InvalidPathException e) {
            return ToolResult.error("Invalid file path");
        }
        if (!Files.isRegularFile(path)) {
            return ToolResult.error("Not a file: " + path);
        }
        byte[] bytes;
        try (var in = Files.newInputStream(path)) {
            bytes = in.readNBytes(MAX_BYTES + 1);
        }
        if (bytes.length > MAX_BYTES) {
            return ToolResult.error("File exceeds 1,000,000 bytes");
        }
        String text;
        try {
            text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException e) {
            return ToolResult.error("File is not valid UTF-8 text");
        }
        if (text.indexOf('\0') >= 0) {
            return ToolResult.error("File contains binary data");
        }
        int start = text.indexOf(oldText);
        if (start < 0) {
            return ToolResult.error("oldText was not found; read the file and match its exact contents");
        }
        if (text.indexOf(oldText, start + 1) >= 0) {
            return ToolResult.error("oldText matches more than once; include more context");
        }
        byte[] result = (text.substring(0, start) + newText + text.substring(start + oldText.length()))
                .getBytes(StandardCharsets.UTF_8);
        if (result.length > MAX_BYTES) {
            return ToolResult.error("Edited file would exceed 1,000,000 bytes");
        }
        try (var in = Files.newInputStream(path)) {
            if (!java.util.Arrays.equals(bytes, in.readNBytes(MAX_BYTES + 1))) {
                return ToolResult.error("File changed during editing; read it again before retrying");
            }
        }
        Files.write(path, result);
        return ToolResult.ok("Edited " + path);
    }
}
