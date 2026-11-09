package net.talaatharb.harness.tools;

import java.util.Map;

/** Normalizes optional control fields only. Never apply placeholder handling to user content or required data. */
public final class ToolArguments {
    private ToolArguments() {}

    public static boolean omitted(Object value) {
        return value == null || value instanceof String text
                && (text.isBlank() || text.strip().equalsIgnoreCase("default") || text.strip().equalsIgnoreCase("null"));
    }

    public static String string(Map<String, Object> args, String key, String fallback) {
        Object value = args == null ? null : args.get(key);
        if (omitted(value) && fallback != null) {
            return fallback;
        }
        if (!(value instanceof String text) || text.isBlank()) {
            throw new IllegalArgumentException("'" + key + "' must be a non-empty string");
        }
        return text;
    }

    public static int integer(Map<String, Object> args, String key, int fallback, int max) {
        return integer(args, key, fallback, 1, max);
    }

    public static int integer(Map<String, Object> args, String key, int fallback, int min, int max) {
        Object value = args == null ? null : args.get(key);
        if (omitted(value)) {
            return fallback;
        }
        try {
            if (value instanceof Number || value instanceof String) {
                int n = new java.math.BigDecimal(value.toString().strip()).intValueExact();
                if (n >= min && n <= max) {
                    return n;
                }
            }
        } catch (NumberFormatException | ArithmeticException e) {
            // Report the field and its contract, not model-supplied values which may contain secrets.
        }
        throw new IllegalArgumentException("'" + key + "' must be an integer from " + min + " to " + max);
    }

    public static boolean bool(Map<String, Object> args, String key, boolean fallback) {
        Object value = args == null ? null : args.get(key);
        if (omitted(value)) {
            return fallback;
        }
        if (value instanceof String text) {
            if (text.strip().equalsIgnoreCase("true")) {
                return true;
            }
            if (text.strip().equalsIgnoreCase("false")) {
                return false;
            }
        }
        if (!(value instanceof Boolean b)) {
            throw new IllegalArgumentException("'" + key + "' must be a boolean");
        }
        return b;
    }
}
