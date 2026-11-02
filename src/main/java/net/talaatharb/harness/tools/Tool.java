package net.talaatharb.harness.tools;

import java.util.Map;

/** A capability the agent may invoke. Implementations are bound via Guice or supplied by MCP servers. */
public interface Tool {

    String name();

    String description();

    /** Human/model readable description of the expected JSON arguments. */
    String argumentsHint();

    default RiskLevel risk() {
        return RiskLevel.SAFE;
    }

    /** Where the tool comes from, e.g. {@code builtin} or {@code mcp:server-name}. */
    default String source() {
        return "builtin";
    }

    ToolResult execute(Map<String, Object> arguments) throws Exception;
}
