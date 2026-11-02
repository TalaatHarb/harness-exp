package net.talaatharb.harness.reply;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Declarative UI node produced by the model. Rendered by {@code DynamicUiRenderer}.
 *
 * @param type     component type (see {@link ReplyFormat#COMPONENT_TYPES})
 * @param text     primary text (label, heading, button caption...)
 * @param action   for buttons: message sent back to the agent when clicked
 * @param value    numeric value (progress 0..1)
 * @param items    list items
 * @param columns  table headers
 * @param rows     table rows
 * @param children nested components for containers
 * @param props    free-form extra properties
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record UiComponent(String type, String text, String action, Double value, List<String> items,
        List<String> columns, List<List<String>> rows, List<UiComponent> children, Map<String, Object> props) {

    public UiComponent {
        items = items == null ? List.of() : items;
        columns = columns == null ? List.of() : columns;
        rows = rows == null ? List.of() : rows;
        children = children == null ? List.of() : children;
        props = props == null ? Map.of() : props;
    }
}
