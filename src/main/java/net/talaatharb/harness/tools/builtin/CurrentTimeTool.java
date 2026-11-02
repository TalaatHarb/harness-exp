package net.talaatharb.harness.tools.builtin;

import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;

import net.talaatharb.harness.tools.Tool;
import net.talaatharb.harness.tools.ToolResult;

public class CurrentTimeTool implements Tool {

    @Override
    public String name() {
        return "current_time";
    }

    @Override
    public String description() {
        return "Returns the current local date, time and time zone.";
    }

    @Override
    public String argumentsHint() {
        return "{}";
    }

    @Override
    public ToolResult execute(Map<String, Object> arguments) {
        return ToolResult.ok(ZonedDateTime.now().format(DateTimeFormatter.RFC_1123_DATE_TIME));
    }
}
