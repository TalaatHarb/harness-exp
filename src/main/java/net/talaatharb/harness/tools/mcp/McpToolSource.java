package net.talaatharb.harness.tools.mcp;

import java.util.List;

import net.talaatharb.harness.tools.Tool;

/**
 * Extension point that turns configured MCP servers into {@link Tool}s.
 * Swap the Guice binding for a real client implementation (stdio / streamable HTTP).
 */
public interface McpToolSource {

    List<Tool> tools(List<McpServerConfig> servers);

    /** Short human-readable status per server for the settings UI. */
    String status(McpServerConfig server);
}
