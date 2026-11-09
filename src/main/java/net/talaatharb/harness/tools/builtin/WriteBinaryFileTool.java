package net.talaatharb.harness.tools.builtin;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.inject.Inject;

import net.talaatharb.harness.agent.ConversationScope;
import net.talaatharb.harness.history.ArtifactStore;
import net.talaatharb.harness.tools.RiskLevel;
import net.talaatharb.harness.tools.Tool;
import net.talaatharb.harness.tools.ToolArguments;
import net.talaatharb.harness.tools.ToolResult;

/** Saves binary bytes without converting them through text encoding. */
public class WriteBinaryFileTool implements Tool {
    private final ArtifactStore artifacts;

    @Inject
    public WriteBinaryFileTool(ArtifactStore artifacts) {
        this.artifacts = artifacts;
    }

    @Override
    public String name() {
        return "write_binary_file";
    }

    @Override
    public String description() {
        return "Saves binary files such as images and audio, up to 20 MiB. Supply exactly one of base64 "
                + "(standard Base64, not a data URL) or sourcePath (an existing local file, copied without changing it). "
                + "By default creates a downloadable conversation artifact: path must be a plain filename. "
                + "Use artifact:false to write to an ordinary local path, replacing it. No append. "
                + "Prefer sourcePath over passing large Base64 through the model. Inline MCP binary results are "
                + "already saved as artifacts; do not rewrite them. URLs and MCP resource links are not fetched. "
                + "Only claim a file was saved after the tool succeeds.";
    }

    @Override
    public String argumentsHint() {
        return "{\"path\":\"image.png\", \"base64\":\"encoded bytes OR omit and use sourcePath\", "
                + "\"sourcePath\":\"existing local file OR omit and use base64\", \"artifact\":true}";
    }

    @Override
    public RiskLevel risk() {
        return RiskLevel.SENSITIVE;
    }

    @Override
    public ToolResult execute(Map<String, Object> arguments) throws IOException {
        if (arguments == null || !(arguments.get("path") instanceof String name) || name.isBlank()) {
            return ToolResult.error("Missing required string argument 'path'");
        }
        boolean hasBase64 = !ToolArguments.omitted(arguments.get("base64"));
        boolean hasSource = !ToolArguments.omitted(arguments.get("sourcePath"));
        if (hasBase64 == hasSource) {
            return ToolResult.error("Supply exactly one of 'base64' or 'sourcePath'");
        }
        try {
            boolean downloadable = ToolArguments.bool(arguments, "artifact", true);
            if (ToolArguments.bool(arguments, "append", false)) {
                return ToolResult.error("Binary writes do not support append:true");
            }
            if (downloadable && ConversationScope.current() == null) {
                return ToolResult.error("Downloadable files require an active conversation");
            }
            byte[] content;
            if (hasBase64) {
                if (!(arguments.get("base64") instanceof String encoded)) {
                    return ToolResult.error("'base64' must be a string");
                }
                content = ArtifactStore.decodeBase64(encoded);
            } else {
                if (!(arguments.get("sourcePath") instanceof String source) || source.isBlank()) {
                    return ToolResult.error("'sourcePath' must be a nonblank string");
                }
                content = ArtifactStore.readBinary(Path.of(source));
            }
            if (downloadable) {
                var artifact = artifacts.writeBinary(ConversationScope.current(), name, content);
                return ToolResult.ok(ArtifactStore.ARTIFACT_PREFIX + new ObjectMapper().writeValueAsString(artifact));
            }
            Path destination = Path.of(name).toAbsolutePath().normalize();
            if (Files.isDirectory(destination)) {
                return ToolResult.error("Path is a directory: " + destination);
            }
            Files.createDirectories(destination.getParent());
            Files.write(destination, content);
            return ToolResult.ok("Wrote " + content.length + " bytes to " + destination);
        } catch (IllegalArgumentException e) {
            return ToolResult.error(e.getMessage());
        }
    }
}
