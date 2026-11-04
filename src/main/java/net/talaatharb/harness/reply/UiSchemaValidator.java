package net.talaatharb.harness.reply;

import java.util.HashSet;
import java.util.Set;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.DeserializationFeature;

/** Bounded, strict validation shared by the model repair loop and the renderer. */
public final class UiSchemaValidator {
    public static final int MAX_DEPTH = 8;
    public static final int MAX_NODES = 200;
    public static final int MAX_BLOCK_CHARS = 65_536;
    public static final int MAX_TEXT_CHARS = 8_192;
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private static final Set<String> FIELDS = Set.of("type", "text", "action", "value", "items", "columns",
            "rows", "children", "props");
    private static final Set<String> CONTAINERS = Set.of("column", "row", "card", "form");
    private static final Set<String> INPUT_PROPS = Set.of("name", "inputType", "required", "value", "placeholder");

    private UiSchemaValidator() {
    }

    public static UiComponent validateJson(String json) {
        require(json != null && json.length() <= MAX_BLOCK_CHARS, "UI block exceeds 65536 characters");
        try {
            JsonNode tree = MAPPER.readTree(json);
            walk(tree, 0, new int[1], null);
            return MAPPER.treeToValue(tree, UiComponent.class);
        } catch (IllegalArgumentException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalArgumentException("Invalid UI JSON", ex);
        }
    }

    public static void validate(UiComponent component) {
        require(component != null, "UI component is required");
        walk(MAPPER.valueToTree(component), 0, new int[1], null);
    }

    private static void walk(JsonNode node, int depth, int[] count, Set<String> formNames) {
        require(depth <= MAX_DEPTH, "UI nesting exceeds " + MAX_DEPTH);
        require(++count[0] <= MAX_NODES, "UI exceeds " + MAX_NODES + " components");
        require(node != null && node.isObject(), "UI component must be an object");
        node.fieldNames().forEachRemaining(key -> require(FIELDS.contains(key), "Unknown UI field: " + key));
        String type = text(node.get("type"), "type", true);
        require(ReplyFormat.COMPONENT_TYPES.contains(type), "Unsupported UI type: " + type);
        text(node.get("text"), "text", false);
        text(node.get("action"), "action", false);
        JsonNode value = node.get("value");
        if (value != null && !value.isNull()) {
            require(value.isNumber() && Double.isFinite(value.doubleValue()), "value must be a finite number");
            require("progress".equals(type) && value.doubleValue() >= 0 && value.doubleValue() <= 1,
                    "progress value must be between 0 and 1");
        }
        strings(node.get("items"), "items", 100);
        strings(node.get("columns"), "columns", 20);
        JsonNode rows = node.get("rows");
        if (rows != null && !rows.isNull()) {
            require(rows.isArray() && rows.size() <= 100, "rows must be an array of at most 100 rows");
            for (JsonNode row : rows) {
                strings(row, "row", 20);
                require(row.isArray(), "table row must be an array");
                require(node.path("columns").isArray() && row.size() <= node.path("columns").size(),
                        "table row exceeds column count");
            }
        }
        JsonNode props = node.get("props");
        require(props == null || props.isNull() || props.isObject(), "props must be an object");
        if (props != null && props.isObject()) {
            Set<String> allowed = "input".equals(type) ? INPUT_PROPS
                    : "form".equals(type) ? Set.of("submitLabel") : Set.of();
            props.fieldNames().forEachRemaining(key -> {
                require(allowed.contains(key), "Unknown " + type + " prop: " + key);
                require(!props.get(key).isNull(), type + " prop cannot be null: " + key);
            });
        }
        if ("form".equals(type)) {
            require(formNames == null, "Forms cannot be nested");
            text(node.get("action"), "form action", true);
            if (props != null) {
                text(props.get("submitLabel"), "submitLabel", false);
            }
            formNames = new HashSet<>();
        }
        if ("input".equals(type)) {
            require(formNames != null, "Inputs must be inside a form");
            require(props != null && props.isObject(), "Input props are required");
            String name = text(props.get("name"), "input name", true);
            require(name.matches("[A-Za-z][A-Za-z0-9_-]{0,63}"), "Invalid input name: " + name);
            require(formNames.add(name), "Duplicate input name: " + name);
            require(formNames.size() <= 32, "Form exceeds 32 inputs");
            String inputType = props.has("inputType") ? text(props.get("inputType"), "inputType", true) : "text";
            require(Set.of("text", "date", "number").contains(inputType), "Unsupported inputType: " + inputType);
            JsonNode required = props.get("required");
            require(required == null || required.isBoolean(), "required must be boolean");
            text(props.get("placeholder"), "placeholder", false);
            String initial = text(props.get("value"), "input value", false);
            if (initial != null && !initial.isBlank()) {
                require(FormSubmission.fieldError(inputType, initial, false) == null, "Invalid initial " + inputType + " value");
            }
        }
        JsonNode children = node.get("children");
        if (children != null && !children.isNull()) {
            require(children.isArray(), "children must be an array");
            require(children.isEmpty() || CONTAINERS.contains(type), type + " cannot have children");
            for (JsonNode child : children) {
                walk(child, depth + 1, count, formNames);
            }
        }
    }

    private static void strings(JsonNode node, String name, int max) {
        if (node == null || node.isNull()) {
            return;
        }
        require(node.isArray() && node.size() <= max, name + " must be an array of at most " + max + " strings");
        for (JsonNode item : node) {
            require(item.isTextual(), name + " item must be a string");
            text(item, name + " item", false);
        }
    }

    private static String text(JsonNode node, String name, boolean required) {
        if (node == null || node.isNull()) {
            require(!required, name + " is required");
            return null;
        }
        require(node.isTextual(), name + " must be a string");
        String text = node.textValue();
        require(text.length() <= MAX_TEXT_CHARS, name + " exceeds " + MAX_TEXT_CHARS + " characters");
        require(!required || !text.isBlank(), name + " cannot be blank");
        return text;
    }

    private static void require(boolean valid, String message) {
        if (!valid) {
            throw new IllegalArgumentException(message);
        }
    }
}
