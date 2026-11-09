package net.talaatharb.harness.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.control.TitledPane;
import javafx.scene.control.TextArea;
import javafx.scene.layout.VBox;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;
import javafx.stage.FileChooser;
import com.fasterxml.jackson.databind.ObjectMapper;
import net.talaatharb.harness.history.ArtifactStore;
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
    private static final Logger LOG = LoggerFactory.getLogger(AssistantTurnView.class);
    private static final int TRACE_PREVIEW_CHARS = 160;

    private final ReplySettings settings;
    private final Consumer<String> actionSink;
    private final Runnable onRegenerate;
    private final VBox root = new VBox(6);
    private final Label stream = ChatBubbles.textLabel("…", "bubble", "bubble-assistant", "streaming");
    private final BooleanProperty imagesAllowed;
    /** Markdown bubbles of the final reply with their raw-text alternatives (for the Raw/Markdown toggle). */
    private final List<Node[]> markdownBubbles = new ArrayList<>();
    private boolean receivedTokens;
    private net.talaatharb.harness.trace.RunTimeline timeline;
    private ArtifactStore artifacts;
    private String conversationId;
    private final java.util.Set<String> shownArtifacts = new java.util.HashSet<>();

    void setArtifacts(ArtifactStore artifacts, String conversationId) {
        this.artifacts = artifacts;
        this.conversationId = conversationId;
    }

    AssistantTurnView(ReplySettings settings, Consumer<String> actionSink, Runnable onRegenerate) {
        this(settings, actionSink, onRegenerate, new SimpleBooleanProperty(settings.allowImages));
    }

    /**
     * @param actionSink    receives messages from buttons in generated UI
     * @param onRegenerate  reruns this turn; {@code null} hides the regenerate button
     * @param imagesAllowed shared "allow all images" switch for Markdown images
     */
    AssistantTurnView(ReplySettings settings, Consumer<String> actionSink, Runnable onRegenerate,
            BooleanProperty imagesAllowed) {
        this.settings = settings;
        this.actionSink = actionSink;
        this.onRegenerate = onRegenerate;
        this.imagesAllowed = imagesAllowed;
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
        outcome(call.name(), result.success(), result.output());
        if (settings.showToolTrace) {
            traceResult(call.name(), result.success(), result.output());
        }
    }

    /** Rebuilds the tool trace of a stored turn from its transcript messages. */
    void replay(ChatMessage message) {
        if (message.role() == Role.ASSISTANT) {
            ToolCallParser.parse(message.content()).ifPresent(this::toolCall);
        } else if (message.role() == Role.TOOL) {
            String content = message.content();
            int newline = content.indexOf('\n');
            String header = newline < 0 ? content : content.substring(0, newline);
            boolean success = header.contains("(success)");
            String output = newline < 0 ? "" : content.substring(newline + 1);
            outcome(message.name(), success, output);
            if (settings.showToolTrace) {
                traceResult(message.name(), success, output);
            }
        }
    }

    private void outcome(String name, boolean success, String output) {
        String out = output == null ? "" : output;
        if (success && artifacts != null) {
            out.lines().filter(line -> line.startsWith(ArtifactStore.ARTIFACT_PREFIX))
                    .forEach(line -> showArtifact(line.substring(ArtifactStore.ARTIFACT_PREFIX.length())));
        }
        boolean denied = !success && (out.startsWith("The user denied this tool call")
                || out.startsWith("Tool call blocked by guardrails") || out.startsWith("Tool call aborted"));
        String status = success ? "Succeeded" : denied ? "Denied" : "Failed";
        String preview = out.replace('\n', ' ');
        if (preview.length() > TRACE_PREVIEW_CHARS) {
            preview = preview.substring(0, TRACE_PREVIEW_CHARS) + "…";
        }
        Label details = ChatBubbles.textLabel(out, "tool-outcome-details");
        String completion = success ? "" : " · action not completed";
        TitledPane pane = new TitledPane("Harness · " + status + " · " + name + completion + ": " + preview, details);
        pane.setExpanded(success && "remember".equals(name));
        pane.getStyleClass().addAll("tool-outcome", success ? "trace-ok" : "trace-error");
        addBeforeStream(pane);
    }

    private void showArtifact(String json) {
        try {
            ArtifactStore.Artifact artifact = new ObjectMapper().readValue(json, ArtifactStore.Artifact.class);
            // Never offer paths supplied by model text: resolve only managed files in this conversation.
            java.nio.file.Path file = artifacts.file(conversationId, artifact);
            if (!shownArtifacts.add(artifact.id())) {
                return;
            }
            Button save = new Button("Save as…");
            save.getStyleClass().add("artifact-save");
            save.setOnAction(e -> {
                FileChooser chooser = new FileChooser();
                chooser.setTitle("Save " + artifact.name());
                chooser.setInitialFileName(artifact.name());
                var destination = chooser.showSaveDialog(root.getScene().getWindow());
                if (destination != null) {
                    try {
                        artifacts.saveAs(conversationId, artifact, destination.toPath());
                    } catch (java.io.IOException | RuntimeException failure) {
                        LOG.error("Could not save artifact {}", artifact.id(), failure);
                        Alert alert = new Alert(Alert.AlertType.ERROR, failure.getMessage(), ButtonType.OK);
                        alert.setHeaderText("Save file failed");
                        alert.initOwner(root.getScene().getWindow());
                        alert.showAndWait();
                    }
                }
            });
            List<Node> row = new ArrayList<>(List.of(new Label("File · " + artifact.name())));
            Button play = ArtifactPreview.playButton(file, artifact.name());
            if (play != null) {
                row.add(play);
            }
            row.add(save);
            addBeforeStream(ChatBubbles.actionBar(Pos.CENTER_LEFT, row.toArray(Node[]::new)));
            Node preview = ArtifactPreview.preview(file, artifact.name());
            if (preview != null) {
                addBeforeStream(preview);
            }
        } catch (java.io.IOException | RuntimeException e) {
            LOG.warn("Could not load conversation artifact", e);
            notice("Downloadable file unavailable: " + e.getMessage());
        }
    }

    void showContext(List<ChatMessage> messages) {
        if (!settings.showContext) {
            return;
        }
        TextArea context = new TextArea(contextText(messages));
        context.setEditable(false);
        context.setWrapText(true);
        context.setPrefRowCount(12);
        TitledPane pane = new TitledPane("Context sent to model (" + messages.size() + " messages)", context);
        pane.setExpanded(false);
        addBeforeStream(pane);
    }

    static String contextText(List<ChatMessage> messages) {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < messages.size(); i++) {
            ChatMessage message = messages.get(i);
            text.append("── ").append(i + 1).append(" · ").append(message.role());
            if (message.name() != null) {
                text.append(" · ").append(message.name());
            }
            text.append(" ──\n").append(message.contentForModel()).append("\n\n");
        }
        return text.toString();
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
        String partial = result.reply();
        if ((partial == null || partial.isBlank()) && receivedTokens) {
            partial = stream.getText();
        }
        root.getChildren().remove(stream);
        if (result.status() == AgentRunResult.Status.COMPLETED) {
            for (ReplySegment segment : ReplyFormat.parse(result.reply(), settings.mode)) {
                if (segment instanceof ReplySegment.Text text) {
                    root.getChildren().add(textBubble(text.text()));
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
            if (result.status() != AgentRunResult.Status.CANCELLED) {
                partial = null;
            }
            if (partial != null && !partial.isBlank()) {
                // Incomplete UI is deliberately shown as text, never as an actionable form.
                root.getChildren().add(ChatBubbles.textLabel(partial, "bubble", "bubble-assistant"));
            }
            String text = statusText(result) + (result.reason() == null ? "" : ": " + result.reason());
            root.getChildren().add(ChatBubbles.textLabel(text,
                    "notice", "notice-" + result.status().name().toLowerCase()));
            addActions(partial == null || partial.isBlank() ? text : partial);
        }
    }

    private Node textBubble(String text) {
        Label raw = ChatBubbles.textLabel(text, "bubble", "bubble-assistant");
        if (!settings.renderMarkdown) {
            return raw;
        }
        try {
            Node markdown = new ChatMarkdownView(text, imagesAllowed, UiSupport::confirmOpenLink, this::localArtifact);
            markdownBubbles.add(new Node[] { markdown, raw });
            return markdown;
        } catch (RuntimeException | LinkageError e) {
            LOG.error("Markdown rendering failed; showing raw text", e);
            return raw;
        }
    }

    /** Resolves a Markdown image reference such as {@code mcp-image-2.png} to this conversation's artifact. */
    private java.util.Optional<java.nio.file.Path> localArtifact(String reference) {
        if (artifacts == null || reference == null) {
            return java.util.Optional.empty();
        }
        String name = reference.strip();
        name = name.startsWith("./") ? name.substring(2) : name.startsWith("artifact:") ? name.substring(9) : name;
        try {
            return artifacts.findByName(conversationId, name);
        } catch (java.io.IOException | RuntimeException e) {
            LOG.warn("Could not resolve artifact reference {}", reference, e);
            return java.util.Optional.empty();
        }
    }

    /** Switches this reply between rendered Markdown and the raw model text. */
    private Button rawToggle() {
        Button button = new Button("Raw");
        button.getStyleClass().addAll("copy-button", "raw-toggle");
        button.setFocusTraversable(false);
        button.setTooltip(new Tooltip("Show the raw model text / the rendered Markdown"));
        button.setOnAction(e -> {
            boolean showRaw = "Raw".equals(button.getText());
            for (Node[] pair : markdownBubbles) {
                Node from = showRaw ? pair[0] : pair[1];
                Node to = showRaw ? pair[1] : pair[0];
                int index = root.getChildren().indexOf(from);
                if (index >= 0) {
                    root.getChildren().set(index, to);
                }
            }
            button.setText(showRaw ? "Markdown" : "Raw");
        });
        return button;
    }

    /** Shown (collapsed) under the reply when {@link #finish} runs; also kept for persisting the turn. */
    void setTimeline(net.talaatharb.harness.trace.RunTimeline timeline) {
        this.timeline = timeline;
    }

    net.talaatharb.harness.trace.RunTimeline timeline() {
        return timeline;
    }

    private void addTimeline() {
        if (!settings.showTimeline || timeline == null || timeline.events.isEmpty()) {
            return;
        }
        TextArea text = new TextArea(timeline.toText());
        text.setEditable(false);
        text.setWrapText(false);
        text.setPrefRowCount(Math.min(14, timeline.events.size() + 4));
        text.getStyleClass().add("mono");
        TitledPane pane = new TitledPane("Timeline · " + timeline.headline(), text);
        pane.setExpanded(false);
        pane.getStyleClass().add("run-timeline");
        root.getChildren().add(pane);
    }

    private void addActions(String text) {
        addTimeline();
        List<Node> actions = new ArrayList<>();
        if (text != null && !text.isBlank()) {
            actions.add(ChatBubbles.copyButton(() -> text));
        }
        if (!markdownBubbles.isEmpty()) {
            actions.add(rawToggle());
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
