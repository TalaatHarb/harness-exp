package net.talaatharb.harness.tools;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import net.talaatharb.harness.tools.mcp.McpServerConfig;

@JsonIgnoreProperties(ignoreUnknown = true)
public class ToolSettings {
    public boolean toolsEnabled = true;
    public Set<String> disabledTools = new HashSet<>();
    public List<McpServerConfig> mcpServers = new ArrayList<>();
    /** Tool output longer than this is truncated before being fed back to the model. */
    public int maxToolOutputChars = 8_000;
}
