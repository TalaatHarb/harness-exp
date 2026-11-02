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

    /**
     * {@code true} for tools whose behaviour can't be reviewed once and trusted (e.g. agent-written code): every call
     * needs human approval regardless of the policy or the "always allow" list.
     */
    default boolean alwaysRequiresApproval() {
        return false;
    }

    /** Where the tool comes from, e.g. {@code builtin} or {@code mcp:server-name}. */
    default String source() {
        return "builtin";
    }

    ToolResult execute(Map<String, Object> arguments) throws Exception;
}
