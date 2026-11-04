package net.talaatharb.harness.reply;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Protocol and parser for agentic UI replies. Pure Java so it is testable without JavaFX. */
public final class ReplyFormat {
    private static final Logger LOG = LoggerFactory.getLogger(ReplyFormat.class);
    public static final String FENCE = "ui";
    public static final List<String> COMPONENT_TYPES = List.of("column", "row", "card", "heading", "text",
            "button", "list", "table", "progress", "badge", "separator", "form", "input");

    public static final String MODEL_INSTRUCTIONS = """
            ## Reply format
            You may enrich replies with interactive UI. To do so, include a fenced block tagged `ui`
            containing ONE JSON component. Component types: column, row, card (containers with "children"),
            heading, text, badge ("text"), button ("text" + "action" = message sent back to you when clicked),
            list ("items"), table ("columns" + "rows"), progress ("value" 0..1), separator.
            For editable fields use a form with "action" (message to send), "children", optional
            "props":{"submitLabel":"Send"}. Inputs belong inside one form and use
            {"type":"input","text":"Label","props":{"name":"unique_name","inputType":"text",
            "required":true,"value":"","placeholder":"Optional hint"}}.
            inputType is text, date (YYYY-MM-DD), or number. A form submits its current named values
            only; it does not book, purchase, or execute anything externally. Never imply otherwise.
            Maximums: 8 nesting levels, 200 components per block, 32 inputs per form, 8192 chars
            per string, 100 list items/table rows, 20 table columns, 65536 chars per UI block.
            Use only documented fields and props; no scripts, HTML, external links, or nested forms.
            Example:
            ```ui
            {"type":"card","text":"Next steps","children":[
              {"type":"list","items":["Install Ollama","Pull a model"]},
              {"type":"button","text":"Show me how","action":"Explain how to pull a model"}]}
            ```
            Use UI only when it genuinely helps; otherwise reply with plain text.""";

    private static final Pattern BLOCK = Pattern.compile("```\\s*" + FENCE + "\\s*\\n(.*?)```", Pattern.DOTALL);
    private ReplyFormat() {
    }

    public static List<ReplySegment> parse(String reply, ReplySettings.Mode mode) {
        List<ReplySegment> segments = new ArrayList<>();
        if (reply == null || reply.isEmpty()) {
            return segments;
        }
        if (mode != ReplySettings.Mode.AGENTIC_UI) {
            segments.add(new ReplySegment.Text(reply.strip()));
            return segments;
        }
        Matcher m = BLOCK.matcher(reply);
        int last = 0;
        while (m.find()) {
            addText(segments, reply.substring(last, m.start()));
            String json = m.group(1).trim();
            try {
                segments.add(new ReplySegment.Ui(UiSchemaValidator.validateJson(json)));
            } catch (Exception e) {
                LOG.warn("Invalid ui block in model reply: {}", e.getMessage());
                segments.add(new ReplySegment.Invalid(json, e.getMessage()));
            }
            last = m.end();
        }
        addText(segments, reply.substring(last));
        return segments;
    }

    /** Deterministic errors for bounded model repair; does not initialize JavaFX. */
    public static List<String> validate(String reply, ReplySettings.Mode mode) {
        if (mode != ReplySettings.Mode.AGENTIC_UI || reply == null) {
            return List.of();
        }
        List<String> errors = new ArrayList<>();
        Matcher matcher = BLOCK.matcher(reply);
        while (matcher.find()) {
            try {
                UiSchemaValidator.validateJson(matcher.group(1).trim());
            } catch (IllegalArgumentException ex) {
                errors.add(ex.getMessage());
            }
        }
        if (reply.replaceAll("(?s)```\\s*ui\\s*\\n.*?```", "").matches("(?s).*```\\s*ui\\b.*")) {
            errors.add("Unclosed or malformed ui fence");
        }
        return List.copyOf(errors);
    }

    private static void addText(List<ReplySegment> segments, String text) {
        if (!text.isBlank()) {
            segments.add(new ReplySegment.Text(text.strip()));
        }
    }
}
