package net.talaatharb.harness.reply;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

/** Protocol and parser for agentic UI replies. Pure Java so it is testable without JavaFX. */
public final class ReplyFormat {
    private static final Logger LOG = LoggerFactory.getLogger(ReplyFormat.class);
    public static final String FENCE = "ui";
    public static final List<String> COMPONENT_TYPES = List.of("column", "row", "card", "heading", "text",
            "button", "list", "table", "progress", "badge", "separator");

    public static final String MODEL_INSTRUCTIONS = """
            ## Reply format
            You may enrich replies with interactive UI. To do so, include a fenced block tagged `ui`
            containing ONE JSON component. Component types: column, row, card (containers with "children"),
            heading, text, badge ("text"), button ("text" + "action" = message sent back to you when clicked),
            list ("items"), table ("columns" + "rows"), progress ("value" 0..1), separator.
            Example:
            ```ui
            {"type":"card","text":"Next steps","children":[
              {"type":"list","items":["Install Ollama","Pull a model"]},
              {"type":"button","text":"Show me how","action":"Explain how to pull a model"}]}
            ```
            Use UI only when it genuinely helps; otherwise reply with plain text.""";

    private static final Pattern BLOCK = Pattern.compile("```\\s*" + FENCE + "\\s*\\n(.*?)```", Pattern.DOTALL);
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.ACCEPT_SINGLE_VALUE_AS_ARRAY, true);

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
                segments.add(new ReplySegment.Ui(MAPPER.readValue(json, UiComponent.class)));
            } catch (Exception e) {
                LOG.warn("Invalid ui block in model reply: {}", e.getMessage());
                segments.add(new ReplySegment.Invalid(json, e.getMessage()));
            }
            last = m.end();
        }
        addText(segments, reply.substring(last));
        return segments;
    }

    private static void addText(List<ReplySegment> segments, String text) {
        if (!text.isBlank()) {
            segments.add(new ReplySegment.Text(text.strip()));
        }
    }
}
