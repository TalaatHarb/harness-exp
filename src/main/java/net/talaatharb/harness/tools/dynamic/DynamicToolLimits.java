package net.talaatharb.harness.tools.dynamic;

import java.util.regex.Pattern;

/** Constants bounding what the agent can create at runtime. */
public final class DynamicToolLimits {
    public static final Pattern NAME_PATTERN = Pattern.compile("[a-z][a-z0-9_]{1,47}");
    public static final int MAX_SOURCE_CHARS = 50_000;
    public static final int MAX_DESCRIPTION_CHARS = 1_000;
    public static final int MAX_DYNAMIC_TOOLS = 50;
    public static final int MAX_DIAGNOSTICS_CHARS = 4_000;
    /** Wall-clock budget for a single dynamic tool execution. The worker thread is interrupted on timeout. */
    public static final long EXECUTION_TIMEOUT_SECONDS = 30;

    private DynamicToolLimits() {
    }
}