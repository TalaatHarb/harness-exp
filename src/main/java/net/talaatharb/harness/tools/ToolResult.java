package net.talaatharb.harness.tools;

public record ToolResult(boolean success, String output) {

    public static ToolResult ok(String output) {
        return new ToolResult(true, output);
    }

    public static ToolResult error(String output) {
        return new ToolResult(false, output);
    }
}
