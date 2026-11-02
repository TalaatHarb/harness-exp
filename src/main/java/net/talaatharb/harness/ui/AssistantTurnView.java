package net.talaatharb.harness.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.TitledPane;
import javafx.scene.layout.VBox;
import net.talaatharb.harness.agent.AgentRunResult;
import net.talaatharb.harness.llm.ChatMessage;
import net.talaatharb.harness.llm.Role;
import net.talaatharb.harness.reply.DynamicUiRenderer;
import net.talaatharb.harness.reply.ReplyFormat;
import net.talaatharb.harness.reply.ReplySegment;
import net.talaatharb.harness.reply.ReplySettings;
import net.talaatharb.harness.tools.ToolCall;
import net.talaatharb.harness.tools.ToolCallParser;
import net.talaatharb.harness.tools.ToolResult;

/** Renders one assistant turn: streamed text, compact tool trace, then the final (possibly generated) UI. */
class AssistantTurnView {
    private static final int TRACE_PREVIEW_CHARS = 160;

    private final ReplySettings settings;
    private final Consumer<String> actionSink;
    private final Runnable onRegenerate;
    private final VBox root = new VBox(6);
    private final Label stream = ChatBubbles.textLabel("…", "bubble", "bubble-assistant", "streaming");
    private boolean receivedTokens;

    /**
     * @param actionSink   receives messages from buttons in generated UI
     * @param onRegenerate reruns this turn; {@code null} hides the regenerate button
     */
    AssistantTurnView(ReplySettings settings, Consumer<String> actionSink, Runnable onRegenerate) {
        this.settings = settings;
        this.actionSink = actionSink;
        this.onRegenerate = onRegenerate;
        root.getStyleClass().add("assistant-turn");
        root.getChildren().add(stream);
    }

    VBox root() {
        return root;
    }

    void appendToken(String token) {
        if (!receivedTokens) {
            stream.setText("");
            receivedTokens = true;
        }
        stream.setText(stream.getText() + token);
    }

    void resetStream() {
        receivedTokens = false;
        stream.setText("…");
    }

    void showThinking(String thinking) {
        if (!settings.showThinking) {
            return;
        }
        Label text = ChatBubbles.textLabel(thinking, "thinking");
        TitledPane pane = new TitledPane("Thinking", text);
        pane.setExpanded(false);
        addBeforeStream(pane);
    }

    void toolCall(ToolCall call) {
        if (settings.showToolTrace) {
            addBeforeStream(ChatBubbles.textLabel("→ " + call.name() + " " + ToolCallParser.toJson(call.arguments()),
                    "trace"));
        }
    }

    void toolResult(ToolCall call, ToolResult result) {
        if (settings.showToolTrace) {
            traceResult(call.name(), result.success(), result.output());
        }
    }

    /** Rebuilds the tool trace of a stored turn from its transcript messages. */
    void replay(ChatMessage message) {
        if (message.role() == Role.ASSISTANT) {
            ToolCallParser.parse(message.content()).ifPresent(this::toolCall);
        } else if (message.role() == Role.TOOL && settings.showToolTrace) {
            String content = message.content();
            int newline = content.indexOf('\n');
            String header = newline < 0 ? content : content.substring(0, newline);
            traceResult(message.name(), header.contains("(success)"), newline < 0 ? "" : content.substring(newline + 1));
        }
    }

    private void traceResult(String name, boolean success, String output) {
        String out = output == null ? "" : output.replace('\n', ' ');
        if (out.length() > TRACE_PREVIEW_CHARS) {
            out = out.substring(0, TRACE_PREVIEW_CHARS) + "…";
        }
        addBeforeStream(ChatBubbles.textLabel((success ? "✓ " : "✗ ") + name + ": " + out,
                "trace", success ? "trace-ok" : "trace-error"));
    }

    void notice(String notice) {
        addBeforeStream(ChatBubbles.textLabel(notice, "notice"));
    }

    void finish(AgentRunResult result) {
        root.getChildren().remove(stream);
        if (result.status() == AgentRunResult.Status.COMPLETED) {
            for (ReplySegment segment : ReplyFormat.parse(result.reply(), settings.mode)) {
                if (segment instanceof ReplySegment.Text text) {
                    root.getChildren().add(ChatBubbles.textLabel(text.text(), "bubble", "bubble-assistant"));
                } else if (segment instanceof ReplySegment.Ui ui) {
                    VBox holder = new VBox(DynamicUiRenderer.render(ui.component(), actionSink));
                    holder.getStyleClass().add("generated-ui");
                    root.getChildren().add(holder);
                } else if (segment instanceof ReplySegment.Invalid invalid) {
                    root.getChildren().add(ChatBubbles.textLabel(
                            "[invalid ui block: " + invalid.error() + "]\n" + invalid.raw(), "notice"));
                }
            }
            if (result.reason() != null) {
                notice(result.reason());
            }
            addActions(result.reply());
        } else {
            String text = statusText(result) + (result.reason() == null ? "" : ": " + result.reason());
            root.getChildren().add(ChatBubbles.textLabel(text,
                    "notice", "notice-" + result.status().name().toLowerCase()));
            addActions(text);
        }
    }

    private void addActions(String text) {
        List<Node> actions = new ArrayList<>();
        if (text != null && !text.isBlank()) {
            actions.add(ChatBubbles.copyButton(() -> text));
        }
        if (onRegenerate != null) {
            actions.add(ChatBubbles.regenerateButton(onRegenerate));
        }
        if (!actions.isEmpty()) {
            root.getChildren().add(ChatBubbles.actionBar(Pos.CENTER_LEFT, actions.toArray(Node[]::new)));
        }
    }

    private static String statusText(AgentRunResult result) {
        switch (result.status()) {
            case BLOCKED:
                return "Blocked by guardrails";
            case LIMIT_REACHED:
                return "Stopped: loop limit reached";
            case CANCELLED:
                return "Cancelled";
            case ABORTED:
                return "Aborted by hook";
            case ERROR:
            default:
                return "Error";
        }
    }

    private void addBeforeStream(javafx.scene.Node node) {
        int index = root.getChildren().indexOf(stream);
        root.getChildren().add(index < 0 ? root.getChildren().size() : index, node);
    }
}
