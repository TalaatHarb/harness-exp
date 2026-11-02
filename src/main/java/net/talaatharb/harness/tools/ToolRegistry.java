package net.talaatharb.harness.tools;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import com.google.inject.Inject;
import com.google.inject.Singleton;

import net.talaatharb.harness.config.HarnessSettings;
import net.talaatharb.harness.tools.mcp.McpToolSource;

/** All known tools (built-in + MCP) and the subset currently enabled. */
@Singleton
public class ToolRegistry {
    private final Set<Tool> builtins;
    private final McpToolSource mcp;

    @Inject
    public ToolRegistry(Set<Tool> builtins, McpToolSource mcp) {
        this.builtins = builtins;
        this.mcp = mcp;
    }

    public List<Tool> all(HarnessSettings settings) {
        List<Tool> tools = new ArrayList<>(builtins);
        tools.addAll(mcp.tools(settings.tools.mcpServers));
        tools.sort(Comparator.comparing(Tool::name));
        return tools;
    }

    public List<Tool> enabled(HarnessSettings settings) {
        if (!settings.tools.toolsEnabled) {
            return List.of();
        }
        return all(settings).stream().filter(t -> !settings.tools.disabledTools.contains(t.name())).toList();
    }

    public Optional<Tool> findEnabled(String name, HarnessSettings settings) {
        return enabled(settings).stream().filter(t -> t.name().equals(name)).findFirst();
    }
}
