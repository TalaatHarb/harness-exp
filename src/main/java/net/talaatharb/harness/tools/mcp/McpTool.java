package net.talaatharb.harness.tools.mcp;

import java.time.Duration;
import java.util.Map;
import java.util.function.Supplier;

import net.talaatharb.harness.tools.RiskLevel;
import net.talaatharb.harness.tools.Tool;
import net.talaatharb.harness.tools.ToolResult;
import net.talaatharb.harness.history.ArtifactStore;

/** Exposes a tool of a connected MCP server as a harness {@link Tool}. */
final class McpTool implements Tool {
    static final int MAX_SCHEMA_CHARS = 2_000;

    private final String name;
    private final McpClient client;
    private final McpClient.ToolInfo info;
    private final Supplier<Duration> timeout;
    private final ArtifactStore artifacts;

    McpTool(String name, McpClient client, McpClient.ToolInfo info, Supplier<Duration> timeout) {
        this(name, client, info, timeout, null);
    }

    McpTool(String name, McpClient client, McpClient.ToolInfo info, Supplier<Duration> timeout, ArtifactStore artifacts) {
        this.name = name;
        this.client = client;
        this.info = info;
        this.timeout = timeout;
        this.artifacts = artifacts;
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public String description() {
        String d = info.description().isBlank() ? info.title() : info.description();
        return (d.isBlank() ? "Tool '" + info.name() + "'" : d.strip()) + " (MCP server '" + client.config().name + "')";
    }

    /** The tool's JSON Schema, which models understand well. */
    @Override
    public String argumentsHint() {
        if (info.inputSchema() == null || info.inputSchema().isMissingNode() || info.inputSchema().isNull()) {
            return "{}";
        }
        String schema = info.inputSchema().toString();
        return schema.length() > MAX_SCHEMA_CHARS ? schema.substring(0, MAX_SCHEMA_CHARS) + "…" : schema;
    }

    /**
     * External code with unknown effects: SENSITIVE unless the server marks the tool read-only, DANGEROUS when it
     * says the tool is destructive. Annotations are hints from the server, so only trust servers you know.
     */
    @Override
    public RiskLevel risk() {
        if (info.destructive()) {
            return RiskLevel.DANGEROUS;
        }
        return info.readOnly() ? RiskLevel.SAFE : RiskLevel.SENSITIVE;
    }

    @Override
    public String source() {
        return "mcp:" + client.config().name;
    }

    @Override
    public ToolResult execute(Map<String, Object> arguments) throws Exception {
        if (!client.transport().isOpen()) {
            return ToolResult.error("MCP server '" + client.config().name + "' is disconnected: "
                    + client.transport().closedReason() + ". Reconnect it in Harness → Tools & MCP.");
        }
        return client.callTool(info.name(), arguments, timeout.get(), artifacts);
    }
}
