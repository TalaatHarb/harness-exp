package net.talaatharb.harness.tools.mcp;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import net.talaatharb.harness.tools.ToolResult;
import net.talaatharb.harness.agent.ConversationScope;
import net.talaatharb.harness.history.ArtifactStore;

/** One initialized MCP session: handshake, {@code tools/list} and {@code tools/call}. */
public class McpClient implements AutoCloseable {
    public static final String PROTOCOL_VERSION = "2025-06-18";
    static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);
    private static final Logger LOG = LoggerFactory.getLogger(McpClient.class);

    private final McpServerConfig config;
    private final McpTransport transport;
    private String serverName = "";
    private String serverVersion = "";
    private String instructions = "";

    /** A tool as advertised by the server. */
    public record ToolInfo(String name, String title, String description, JsonNode inputSchema, boolean readOnly,
            boolean destructive) {
    }

    McpClient(McpServerConfig config, McpTransport transport) {
        this.config = config;
        this.transport = transport;
    }

    /** Starts/opens the transport and runs the {@code initialize} handshake. */
    public static McpClient connect(McpServerConfig config, UnaryOperator<String> env, Duration timeout)
            throws IOException, InterruptedException {
        Map<String, String> resolved = config.resolvedEnv(env);
        McpTransport transport = config.transport == McpServerConfig.Transport.HTTP
                ? new HttpMcpTransport(config.name, config.url, resolved)
                : new StdioMcpTransport(config.name, config.command, resolved);
        McpClient client = new McpClient(config, transport);
        try {
            client.initialize(timeout);
            return client;
        } catch (IOException | InterruptedException | RuntimeException e) {
            transport.close();
            throw e;
        }
    }

    private void initialize(Duration timeout) throws IOException, InterruptedException {
        ObjectNode params = McpTransport.JSON.createObjectNode().put("protocolVersion", PROTOCOL_VERSION);
        params.putObject("capabilities");
        params.putObject("clientInfo").put("name", "harness-exp").put("version", "0.1.0");
        JsonNode result = transport.request("initialize", params, timeout);
        String version = result.path("protocolVersion").asText(PROTOCOL_VERSION);
        if (transport instanceof HttpMcpTransport http) {
            http.protocolVersion(version);
        }
        serverName = result.path("serverInfo").path("name").asText("");
        serverVersion = result.path("serverInfo").path("version").asText("");
        instructions = result.path("instructions").asText("");
        if (!result.path("capabilities").has("tools")) {
            LOG.info("MCP server '{}' doesn't advertise tools", config.name);
        }
        transport.notify("notifications/initialized", null);
        LOG.info("Connected to MCP server '{}' ({} {}, protocol {})", config.name, serverName, serverVersion, version);
    }

    public List<ToolInfo> listTools() throws IOException, InterruptedException {
        List<ToolInfo> tools = new ArrayList<>();
        String cursor = null;
        int pages = 0;
        do {
            ObjectNode params = McpTransport.JSON.createObjectNode();
            if (cursor != null) {
                params.put("cursor", cursor);
            }
            JsonNode result = transport.request("tools/list", params, REQUEST_TIMEOUT);
            for (JsonNode t : result.path("tools")) {
                JsonNode annotations = t.path("annotations");
                tools.add(new ToolInfo(t.path("name").asText(), t.path("title").asText(annotations.path("title")
                        .asText("")), t.path("description").asText(""), t.path("inputSchema"),
                        annotations.path("readOnlyHint").asBoolean(false),
                        annotations.path("destructiveHint").asBoolean(false)));
            }
            cursor = result.hasNonNull("nextCursor") ? result.get("nextCursor").asText() : null;
        } while (cursor != null && !cursor.isEmpty() && ++pages < 50);
        return tools;
    }

    public ToolResult callTool(String name, Map<String, Object> arguments, Duration timeout)
            throws IOException, InterruptedException {
        return callTool(name, arguments, timeout, null);
    }

    public ToolResult callTool(String name, Map<String, Object> arguments, Duration timeout, ArtifactStore artifacts)
            throws IOException, InterruptedException {
        ObjectNode params = McpTransport.JSON.createObjectNode().put("name", name);
        params.set("arguments", McpTransport.JSON.valueToTree(arguments == null ? Map.of() : arguments));
        JsonNode result = transport.request("tools/call", params, timeout);
        String text = result.path("isError").asBoolean(false) ? formatContent(result)
                : formatContent(result, artifacts, ConversationScope.current());
        return result.path("isError").asBoolean(false) ? ToolResult.error(text) : ToolResult.ok(text);
    }

    /** Flattens MCP content blocks into text the model can read. */
    static String formatContent(JsonNode result) throws IOException {
        return formatContent(result, null, null);
    }

    static String formatContent(JsonNode result, ArtifactStore artifacts, String conversationId) throws IOException {
        List<String> parts = new ArrayList<>();
        int block = 0;
        for (JsonNode c : result.path("content")) {
            block++;
            switch (c.path("type").asText()) {
                case "text" -> parts.add(c.path("text").asText());
                case "image", "audio" -> parts.add(artifacts != null
                        ? captureBinary(c, "data", "mcp-" + c.path("type").asText() + "-" + block, artifacts, conversationId)
                        : "[" + c.path("type").asText() + ": "
                        + c.path("mimeType").asText("?") + ", "
                        + approxBytes(c.path("data").asText("")) + " bytes; not shown to the model]");
                case "resource" -> {
                    JsonNode r = c.path("resource");
                    parts.add(r.has("text") ? "[resource " + r.path("uri").asText() + "]\n" + r.path("text").asText()
                            : artifacts != null
                                    ? captureBinary(r, "blob", "mcp-resource-" + block, artifacts, conversationId)
                                    : "[binary resource " + r.path("uri").asText() + " (" + r.path("mimeType").asText("?")
                                    + ")]");
                }
                case "resource_link" -> parts.add("[resource link: " + c.path("uri").asText()
                        + (c.hasNonNull("name") ? " (" + c.path("name").asText() + ")" : "") + "]");
                default -> parts.add(c.toString());
            }
        }
        if (parts.isEmpty() && result.hasNonNull("structuredContent")) {
            parts.add(result.get("structuredContent").toString());
        }
        return parts.isEmpty() ? "(no output)" : String.join("\n", parts);
    }

    private static String captureBinary(JsonNode content, String dataField, String filename, ArtifactStore artifacts,
            String conversationId) throws IOException {
        if (conversationId == null) {
            throw new IOException("MCP binary output cannot be saved without an active conversation");
        }
        if (!content.path(dataField).isTextual()) {
            throw new IOException("MCP binary output requires Base64 string field '" + dataField + "'");
        }
        try {
            byte[] bytes = ArtifactStore.decodeBase64(content.get(dataField).asText());
            String mime = content.path("mimeType").asText("application/octet-stream");
            String extension = switch (mime.split(";", 2)[0].strip().toLowerCase(java.util.Locale.ROOT)) {
                case "image/png" -> ".png";
                case "image/jpeg" -> ".jpg";
                case "image/gif" -> ".gif";
                case "image/webp" -> ".webp";
                case "image/avif" -> ".avif";
                case "image/svg+xml" -> ".svg";
                case "image/bmp" -> ".bmp";
                case "audio/wav", "audio/x-wav", "audio/wave" -> ".wav";
                case "audio/mpeg", "audio/mp3" -> ".mp3";
                case "audio/ogg", "application/ogg" -> ".ogg";
                case "audio/flac" -> ".flac";
                case "audio/mp4", "audio/x-m4a" -> ".m4a";
                case "audio/aac" -> ".aac";
                case "video/mp4" -> ".mp4";
                case "application/pdf" -> ".pdf";
                default -> ".bin";
            };
            var artifact = artifacts.writeBinary(conversationId, filename + extension, bytes);
            return ArtifactStore.ARTIFACT_PREFIX + McpTransport.JSON.writeValueAsString(artifact);
        } catch (IllegalArgumentException e) {
            throw new IOException("Could not save MCP binary output: " + e.getMessage(), e);
        }
    }

    private static int approxBytes(String base64) {
        try {
            return Base64.getDecoder().decode(base64).length;
        } catch (IllegalArgumentException e) {
            return base64.length() * 3 / 4;
        }
    }

    public McpTransport transport() {
        return transport;
    }

    public McpServerConfig config() {
        return config;
    }

    /** e.g. {@code "secure-filesystem-server 0.2.0"}. */
    public String serverInfo() {
        return (serverName + " " + serverVersion).strip();
    }

    public String instructions() {
        return instructions;
    }

    @Override
    public void close() {
        transport.close();
    }
}
