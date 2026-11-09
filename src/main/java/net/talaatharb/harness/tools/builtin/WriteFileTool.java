package net.talaatharb.harness.tools.builtin;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.inject.Inject;

import net.talaatharb.harness.agent.ConversationScope;
import net.talaatharb.harness.history.ArtifactStore;
import net.talaatharb.harness.tools.RiskLevel;
import net.talaatharb.harness.tools.Tool;
import net.talaatharb.harness.tools.ToolArguments;
import net.talaatharb.harness.tools.ToolResult;

/** Writes a local UTF-8 text file. Sensitive: the default approval policy asks the user first. */
public class WriteFileTool implements Tool {
    static final int MAX_CHARS = 1_000_000;
    public static final String ARTIFACT_PREFIX = ArtifactStore.ARTIFACT_PREFIX;
    private final ArtifactStore artifacts;

    public WriteFileTool() {
        this.artifacts = null;
    }

    @Inject
    public WriteFileTool(ArtifactStore artifacts) {
        this.artifacts = artifacts;
    }

    @Override
    public String name() {
        return "write_file";
    }

    @Override
    public String description() {
        return "Writes UTF-8 text to a local file, replacing it unless append is true. Parent folders are created. "
                + "Content is limited to 1,000,000 characters. When the user asks for a file to download "
                + "(e.g. a story in .txt or .md), use artifact:true with a plain filename in path. "
                + "This creates a conversation-owned file and the chat shows a Save as button. "
                + "Only claim a file is available after this tool succeeds. Artifact files do not support append.";
    }

    @Override
    public String argumentsHint() {
        return "{\"path\": \"file path, or filename for an artifact\", \"content\": \"text\", "
                + "\"append\": false, \"artifact\": false}";
    }

    @Override
    public RiskLevel risk() {
        return RiskLevel.SENSITIVE;
    }

    @Override
    public ToolResult execute(Map<String, Object> arguments) throws IOException {
        if (arguments == null) {
            return ToolResult.error("Missing arguments");
        }
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
        boolean append;
        boolean artifactMode;
        Path path;
        try {
            append = ToolArguments.bool(arguments, "append", false);
            artifactMode = ToolArguments.bool(arguments, "artifact", false);
            path = Path.of(p).toAbsolutePath().normalize();
        } catch (IllegalArgumentException e) {
            return ToolResult.error("Write arguments rejected: " + e.getMessage());
        }
        if (artifactMode) {
            if (artifacts == null || ConversationScope.current() == null) {
                return ToolResult.error("Downloadable files require an active conversation and artifact storage");
            }
            if (append) {
                return ToolResult.error("Artifact files do not support append; write the complete file");
            }
            try {
                var artifact = artifacts.write(ConversationScope.current(), p, text);
                return ToolResult.ok(ARTIFACT_PREFIX + new ObjectMapper().writeValueAsString(artifact));
            } catch (IllegalArgumentException e) {
                return ToolResult.error(e.getMessage());
            }
        }
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
