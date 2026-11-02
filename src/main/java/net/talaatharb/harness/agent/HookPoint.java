package net.talaatharb.harness.agent;

/** Points in the agent loop where {@link AgentHook}s are invoked. */
public enum HookPoint {
    /** Before input guardrails; the user message is in {@link AgentRunContext#userMessage()}. */
    BEFORE_RUN,
    /** Before each model call; the assembled context is available and may be inspected. */
    BEFORE_MODEL_CALL,
    /** After each model call; {@link AgentRunContext#lastReply()} is set. */
    AFTER_MODEL_CALL,
    /** Before a tool executes (after guardrails and approval). */
    BEFORE_TOOL_CALL,
    /** After a tool executes; {@link AgentRunContext#lastToolResult()} is set. */
    AFTER_TOOL_CALL,
    /** A runaway-loop limit was hit. */
    ON_LIMIT_REACHED,
    /** A model or tool error occurred. */
    ON_ERROR,
    /** The run finished (any outcome). */
    AFTER_RUN
}
