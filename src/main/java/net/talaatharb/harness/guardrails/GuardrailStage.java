package net.talaatharb.harness.guardrails;

/** Where in the agent loop a guardrail is evaluated. */
public enum GuardrailStage {
    /** User message before it enters the context. */
    INPUT,
    /** Tool call (name + JSON arguments) before execution. */
    TOOL_CALL,
    /** Final assistant reply before it is shown to the user. */
    OUTPUT
}
