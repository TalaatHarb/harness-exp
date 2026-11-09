package net.talaatharb.harness.tools.builtin;

import java.util.Map;

final class ToolArguments {
    private ToolArguments() {}

    static String string(Map<String, Object> args, String key, String fallback) {
        Object value = args.get(key);
        if (value == null && fallback != null) {
            return fallback;
        }
        if (!(value instanceof String text) || text.isBlank()) {
            throw new IllegalArgumentException("'" + key + "' must be a non-empty string");
        }
        return text;
    }

    static int integer(Map<String, Object> args, String key, int fallback, int max) {
        Object value = args.get(key);
        if (value == null) {
            return fallback;
        }
        if (!(value instanceof Number n) || !Double.isFinite(n.doubleValue())
                || n.doubleValue() != Math.rint(n.doubleValue()) || n.doubleValue() < 1 || n.doubleValue() > max) {
            throw new IllegalArgumentException("'" + key + "' must be an integer from 1 to " + max);
        }
        return n.intValue();
    }

    static boolean bool(Map<String, Object> args, String key, boolean fallback) {
        Object value = args.get(key);
        if (value == null) {
            return fallback;
        }
        if (!(value instanceof Boolean b)) {
            throw new IllegalArgumentException("'" + key + "' must be a boolean");
        }
        return b;
    }
}
