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
        ObjectNode params = McpTransport.JSON.createObjectNode().put("name", name);
        params.set("arguments", McpTransport.JSON.valueToTree(arguments == null ? Map.of() : arguments));
        JsonNode result = transport.request("tools/call", params, timeout);
        String text = formatContent(result);
        return result.path("isError").asBoolean(false) ? ToolResult.error(text) : ToolResult.ok(text);
    }

    /** Flattens MCP content blocks into text the model can read. */
    static String formatContent(JsonNode result) {
        List<String> parts = new ArrayList<>();
        for (JsonNode c : result.path("content")) {
            switch (c.path("type").asText()) {
                case "text" -> parts.add(c.path("text").asText());
                case "image", "audio" -> parts.add("[" + c.path("type").asText() + ": "
                        + c.path("mimeType").asText("?") + ", "
                        + approxBytes(c.path("data").asText("")) + " bytes; not shown to the model]");
                case "resource" -> {
                    JsonNode r = c.path("resource");
                    parts.add(r.has("text") ? "[resource " + r.path("uri").asText() + "]\n" + r.path("text").asText()
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
