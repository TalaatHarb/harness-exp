package net.talaatharb.harness.agent;

import java.time.Duration;

/**
 * Hard ceilings that protect against runaway agent loops. User settings can
 * lower these limits but never exceed them.
 */
public final class AgentLoopLimits {
    /** Maximum model calls (reason/act iterations) per user turn. */
    public static final int HARD_MAX_ITERATIONS = 25;
    /** Maximum tool executions per user turn. */
    public static final int HARD_MAX_TOOL_CALLS = 50;
    /** Maximum wall-clock time per user turn. */
    public static final Duration HARD_MAX_DURATION = Duration.ofMinutes(15);
    /** Maximum times the exact same tool call (name + arguments) may repeat in one turn. */
    public static final int HARD_MAX_IDENTICAL_TOOL_CALLS = 5;
    /** Maximum consecutive model/tool errors before the loop gives up. */
    public static final int HARD_MAX_CONSECUTIVE_ERRORS = 5;

    public static final int DEFAULT_MAX_ITERATIONS = 8;
    public static final int DEFAULT_MAX_TOOL_CALLS = 10;
    public static final int DEFAULT_MAX_DURATION_SECONDS = 300;
    public static final int DEFAULT_MAX_IDENTICAL_TOOL_CALLS = 2;
    public static final int DEFAULT_MAX_CONSECUTIVE_ERRORS = 2;

    private AgentLoopLimits() {
    }

    static int clamp(int configured, int hardMax) {
        return Math.max(1, Math.min(configured, hardMax));
    }
}
