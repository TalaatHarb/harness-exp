package net.talaatharb.harness.tools;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import net.talaatharb.harness.tools.mcp.McpServerConfig;
import net.talaatharb.harness.web.WebSearchSettings;

@JsonIgnoreProperties(ignoreUnknown = true)
public class ToolSettings {
    public boolean toolsEnabled = true;
    public Set<String> disabledTools = new HashSet<>();
    public List<McpServerConfig> mcpServers = new ArrayList<>();
    /** Tool output longer than this is truncated before being fed back to the model. */
    public int maxToolOutputChars = 8_000;
    /** How long to wait for an MCP server to start and answer {@code initialize} (npx may download it first). */
    public int mcpConnectTimeoutSeconds = 180;
    /** Maximum time for one MCP tool call. */
    public int mcpToolTimeoutSeconds = 120;
    /** Search provider and limits shared by {@code web_search_extract} and {@code web_search_index}. */
    public WebSearchSettings web = new WebSearchSettings();
}
