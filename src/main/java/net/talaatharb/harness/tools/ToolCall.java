package net.talaatharb.harness.tools;

import java.util.Map;

public record ToolCall(String name, Map<String, Object> arguments) {

    public ToolCall {
        arguments = arguments == null ? Map.of()
                : java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(arguments));
    }

    /** Stable signature used to detect the agent repeating the exact same call. */
    public String signature() {
        return name + new java.util.TreeMap<>(arguments);
    }
}
