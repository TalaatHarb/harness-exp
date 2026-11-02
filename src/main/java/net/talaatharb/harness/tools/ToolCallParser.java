package net.talaatharb.harness.tools;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Parses the provider-agnostic text protocol used for tool calls:
 *
 * <pre>
 * ```tool_call
 * {"name": "current_time", "arguments": {}}
 * ```
 * </pre>
 *
 * A bare JSON object with {@code name}/{@code arguments} as the whole reply is also accepted,
 * since small local models often drop the fence.
 */
public final class ToolCallParser {
    public static final String FENCE = "tool_call";
    private static final Pattern FENCED = Pattern.compile("```\\s*" + FENCE + "\\s*(\\{.*?})\\s*```", Pattern.DOTALL);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Logger LOG = LoggerFactory.getLogger(ToolCallParser.class);

    private ToolCallParser() {
    }

    public static Optional<ToolCall> parse(String reply) {
        if (reply == null || reply.isBlank()) {
            return Optional.empty();
        }
        Matcher m = FENCED.matcher(reply);
        if (m.find()) {
            return toCall(m.group(1), true);
        }
        String trimmed = reply.trim();
        if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
            return toCall(trimmed, false);
        }
        return Optional.empty();
    }

    private static Optional<ToolCall> toCall(String json, boolean explicit) {
        try {
            Map<String, Object> raw = MAPPER.readValue(json, new TypeReference<LinkedHashMap<String, Object>>() {
            });
            Object name = raw.get("name");
            if (!(name instanceof String) || ((String) name).isBlank()) {
                if (explicit) {
                    LOG.warn("Ignoring tool_call block without a tool name: {}", json);
                }
                return Optional.empty();
            }
            Object args = raw.get("arguments");
            @SuppressWarnings("unchecked")
            Map<String, Object> arguments = args instanceof Map ? (Map<String, Object>) args : Map.of();
            return Optional.of(new ToolCall((String) name, arguments));
        } catch (Exception e) {
            if (explicit) {
                LOG.warn("Ignoring malformed tool_call block: {}", e.getMessage());
            }
            return Optional.empty();
        }
    }

    public static String toJson(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (Exception e) {
            LOG.warn("Could not serialize value to JSON", e);
            return String.valueOf(value);
        }
    }
}
