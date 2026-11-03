package net.talaatharb.harness.tools.mcp;

import java.util.List;

import net.talaatharb.harness.tools.Tool;

/** Turns configured MCP servers into {@link Tool}s. The app binds {@link McpClientManager}. */
public interface McpToolSource {

    /** Source with no MCP servers (tests). */
    McpToolSource NONE = new McpToolSource() {
        @Override
        public List<Tool> tools(List<McpServerConfig> servers) {
            return List.of();
        }

        @Override
        public String status(McpServerConfig server) {
            return server.enabled ? "not connected" : "disabled";
        }
    };

    /** Tools of the given servers that are currently connected; must not block. */
    List<Tool> tools(List<McpServerConfig> servers);

    /** Short human-readable status per server for the settings UI. */
    String status(McpServerConfig server);

    /** Drops and re-opens the connection of an applied server. */
    default void reconnect(String serverName) {
    }

    /** Connects to a (possibly unsaved) server once and lists its tools, without keeping the connection. */
    default List<McpClient.ToolInfo> test(McpServerConfig server) throws Exception {
        throw new UnsupportedOperationException("MCP client not available");
    }

    /** Notified (on a background thread) when a server's status or tool list changes. */
    default void addListener(Runnable listener) {
    }

    default void removeListener(Runnable listener) {
    }
}
