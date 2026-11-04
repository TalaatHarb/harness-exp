package net.talaatharb.harness.agent;

import net.talaatharb.harness.tools.ToolCall;
import net.talaatharb.harness.tools.ToolResult;

/** UI-facing callbacks; invoked on the agent thread. */
public interface AgentListener {
    /** Sanitized messages actually sent to the provider, for opt-in context inspection. */
    default void onContext(java.util.List<net.talaatharb.harness.llm.ChatMessage> messages) {
    }

    default void onToken(String token) {
    }

    default void onThinking(String thinking) {
    }

    /** A model reply that is going to be followed by a tool call (intermediate step). */
    default void onIntermediateReply(String reply) {
    }

    default void onToolCall(ToolCall call) {
    }

    default void onToolResult(ToolCall call, ToolResult result) {
    }

    default void onNotice(String notice) {
    }

    /** Terminal callback; always invoked exactly once per run. */
    default void onFinished(AgentRunResult result) {
    }
}
