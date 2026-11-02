package net.talaatharb.harness.tools;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import com.google.inject.Inject;
import com.google.inject.Singleton;

import net.talaatharb.harness.config.HarnessSettings;
import net.talaatharb.harness.tools.dynamic.DynamicToolRegistry;
import net.talaatharb.harness.tools.mcp.McpToolSource;

/** All known tools (built-in + MCP + agent-created) and the subset currently enabled. */
@Singleton
public class ToolRegistry {
    private final Set<Tool> builtins;
    private final McpToolSource mcp;
    private final DynamicToolRegistry dynamic;

    @Inject
    public ToolRegistry(Set<Tool> builtins, McpToolSource mcp, DynamicToolRegistry dynamic) {
        this.builtins = builtins;
        this.mcp = mcp;
        this.dynamic = dynamic;
    }

    /** Built-in tools win over MCP tools, which win over dynamic tools with the same name. */
    public List<Tool> all(HarnessSettings settings) {
        Map<String, Tool> byName = new LinkedHashMap<>();
        builtins.forEach(t -> byName.putIfAbsent(t.name(), t));
        mcp.tools(settings.tools.mcpServers).forEach(t -> byName.putIfAbsent(t.name(), t));
        dynamic.tools().forEach(t -> byName.putIfAbsent(t.name(), t));
        List<Tool> tools = new ArrayList<>(byName.values());
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
