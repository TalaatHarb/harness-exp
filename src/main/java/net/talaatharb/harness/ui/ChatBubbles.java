package net.talaatharb.harness.ui;

import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javafx.animation.PauseTransition;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.Tooltip;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.util.Duration;

/** Small factory for chat bubbles. */
public final class ChatBubbles {
    private static final Logger LOG = LoggerFactory.getLogger(ChatBubbles.class);
    private static final String COPY_TEXT = "⧉ Copy";
    private static final Duration COPIED_FEEDBACK = Duration.seconds(1.5);

    private ChatBubbles() {
    }

    public static Node user(String text) {
        Label bubble = textLabel(text, "bubble", "bubble-user");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox row = new HBox(spacer, bubble);
        row.setAlignment(Pos.CENTER_RIGHT);
        VBox box = new VBox(2, row, actionBar(Pos.CENTER_RIGHT, copyButton(() -> text)));
        box.getStyleClass().add("user-turn");
        return box;
    }

    public static Label textLabel(String text, String... styleClasses) {
        Label label = new Label(text);
        label.setWrapText(true);
        label.setMaxWidth(720);
        label.getStyleClass().addAll(styleClasses);
        MenuItem copy = new MenuItem("Copy");
        copy.setOnAction(e -> copyToClipboard(label.getText()));
        label.setContextMenu(new ContextMenu(copy));
        return label;
    }

    /** A small button that copies the supplied text and briefly confirms it. */
    public static Button copyButton(Supplier<String> text) {
        Button button = new Button(COPY_TEXT);
        button.getStyleClass().add("copy-button");
        button.setFocusTraversable(false);
        button.setTooltip(new Tooltip("Copy to clipboard"));
        PauseTransition reset = new PauseTransition(COPIED_FEEDBACK);
        reset.setOnFinished(e -> button.setText(COPY_TEXT));
        button.setOnAction(e -> {
            button.setText(copyToClipboard(text.get()) ? "✓ Copied" : "✗ Copy failed");
            reset.playFromStart();
        });
        return button;
    }

    static HBox actionBar(Pos alignment, Node... actions) {
        HBox bar = new HBox(4, actions);
        bar.setAlignment(alignment);
        bar.getStyleClass().add("message-actions");
        return bar;
    }

    static boolean copyToClipboard(String text) {
        try {
            ClipboardContent content = new ClipboardContent();
            content.putString(text == null ? "" : text);
            return Clipboard.getSystemClipboard().setContent(content);
        } catch (RuntimeException e) {
            LOG.error("Could not copy to clipboard", e);
            return false;
        }
    }
}
