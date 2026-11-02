package net.talaatharb.harness.tools.mcp;

import java.util.List;

import net.talaatharb.harness.tools.Tool;

/** Placeholder until an MCP client is wired in: servers are configurable but expose no tools yet. */
public class UnconnectedMcpToolSource implements McpToolSource {

    @Override
    public List<Tool> tools(List<McpServerConfig> servers) {
        return List.of();
    }

    @Override
    public String status(McpServerConfig server) {
        return server.enabled ? "configured (client not implemented yet)" : "disabled";
    }
}
