package net.talaatharb.harness.tools;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.core.json.JsonReadFeature;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;

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
 * since small local models often drop the fence. JSON is parsed leniently (raw newlines inside strings, trailing
 * commas), and a {@value #CODE_ARGUMENT} argument may instead be supplied as a separate {@code ```java} block in the
 * same reply, because models struggle to escape whole source files into JSON strings.
 */
public final class ToolCallParser {
    public static final String FENCE = "tool_call";
    public static final String CODE_ARGUMENT = "sourceCode";
    private static final Pattern FENCED = Pattern.compile("```\\s*" + FENCE + "\\s*(\\{.*?})\\s*```", Pattern.DOTALL);
    private static final Pattern OPEN_FENCE = Pattern.compile("```\\s*" + FENCE + "\\b");
    private static final Pattern CODE_BLOCK = Pattern.compile("```[ \\t]*java[ \\t]*\\r?\\n(.*?)```", Pattern.DOTALL);
    private static final ObjectMapper MAPPER = JsonMapper.builder()
            .enable(JsonReadFeature.ALLOW_UNESCAPED_CONTROL_CHARS, JsonReadFeature.ALLOW_TRAILING_COMMA)
            .build();
    private static final Logger LOG = LoggerFactory.getLogger(ToolCallParser.class);

    private ToolCallParser() {
    }

    public static Optional<ToolCall> parse(String reply) {
        if (reply == null || reply.isBlank()) {
            return Optional.empty();
        }
        Matcher m = FENCED.matcher(reply);
        if (m.find()) {
            return toCall(m.group(1), true).map(call -> attachCode(call, reply));
        }
        String trimmed = reply.trim();
        if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
            return toCall(trimmed, false);
        }
        return Optional.empty();
    }

    private static ToolCall attachCode(ToolCall call, String reply) {
        Object existing = call.arguments().get(CODE_ARGUMENT);
        if (!ToolArguments.omitted(existing)) {
            return call;
        }
        Matcher code = CODE_BLOCK.matcher(reply);
        if (!code.find()) {
            return call;
        }
        Map<String, Object> arguments = new LinkedHashMap<>(call.arguments());
        arguments.put(CODE_ARGUMENT, code.group(1).strip());
        return new ToolCall(call.name(), arguments);
    }

    /**
     * Explains why a reply that clearly attempts a tool call (it has a {@code tool_call} fence) could not be parsed,
     * so the loop can ask the model to correct it instead of showing the raw block as the answer.
     */
    public static Optional<String> diagnose(String reply) {
        if (reply == null || parse(reply).isPresent()) {
            return Optional.empty();
        }
        Matcher open = OPEN_FENCE.matcher(reply);
        if (!open.find()) {
            return Optional.empty();
        }
        int end = reply.indexOf("```", open.end());
        String body = (end < 0 ? reply.substring(open.end()) : reply.substring(open.end(), end)).trim();
        try {
            Object value = MAPPER.readValue(body, Object.class);
            if (!(value instanceof Map<?, ?> map) || !(map.get("name") instanceof String)) {
                return Optional.of("expected a JSON object with a string \"name\" and an \"arguments\" object");
            }
            return Optional.of("the block could not be read as a tool call");
        } catch (Exception e) {
            String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            return Optional.of("invalid JSON: " + message.lines().findFirst().orElse(message));
        }
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
            if (!(args instanceof Map) && !ToolArguments.omitted(args)) {
                if (explicit) {
                    LOG.warn("Tool arguments must be a JSON object");
                }
                return Optional.empty();
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> arguments = args instanceof Map ? (Map<String, Object>) args : Map.of();
            return Optional.of(new ToolCall(((String) name).strip(), arguments));
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
