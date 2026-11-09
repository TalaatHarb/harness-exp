package net.talaatharb.harness.ui;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextArea;
import javafx.scene.control.Tooltip;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.scene.media.Media;
import javafx.scene.media.MediaPlayer;
import javafx.stage.Stage;
import javafx.stage.Window;
import net.talaatharb.harness.llm.Attachment;

/** Chips, thumbnails and viewers for message attachments. */
public final class AttachmentViews {
    private static final Logger LOG = LoggerFactory.getLogger(AttachmentViews.class);
    private static final double THUMB_WIDTH = 180;
    private static final double THUMB_HEIGHT = 140;

    private static MediaPlayer player;
    private static Button playing;

    private AttachmentViews() {
    }

    public static String icon(Attachment a) {
        return switch (a.kind()) {
            case TEXT -> "📝";
            case PDF -> "📄";
            case IMAGE -> "🖼";
            case AUDIO -> "🎵";
        };
    }

    /** A compact chip; {@code onRemove} adds an ✕ button (composer), {@code null} for sent messages. */
    public static HBox chip(Attachment a, Runnable onRemove) {
        Label label = new Label(icon(a) + " " + a.name() + " · " + Attachment.formatSize(a.size())
                + (a.kind() == Attachment.Kind.AUDIO && a.text() != null ? " · transcribed" : "")
                + (a.truncated() ? " · truncated" : ""));
        label.setTooltip(new Tooltip(describe(a)));
        HBox chip = new HBox(4, label);
        chip.setAlignment(Pos.CENTER_LEFT);
        chip.getStyleClass().add("attachment-chip");
        if (a.kind() == Attachment.Kind.AUDIO) {
            chip.getChildren().add(playButton(a));
        }
        if (a.kind() != Attachment.Kind.AUDIO || a.text() != null) {
            label.setCursor(Cursor.HAND);
            label.setOnMouseClicked(e -> open(a, chip));
        }
        if (onRemove != null) {
            Button remove = new Button("✕");
            remove.getStyleClass().add("attachment-remove");
            remove.setFocusTraversable(false);
            remove.setTooltip(new Tooltip("Remove attachment"));
            remove.setOnAction(e -> onRemove.run());
            chip.getChildren().add(remove);
        }
        return chip;
    }

    /** Attachments of a sent message: image thumbnails and chips for everything else. */
    public static FlowPane gallery(List<Attachment> attachments, Pos alignment) {
        FlowPane pane = new FlowPane(6, 6);
        pane.setAlignment(alignment);
        pane.getStyleClass().add("attachment-gallery");
        for (Attachment a : attachments) {
            Node node = a.kind() == Attachment.Kind.IMAGE ? thumbnail(a) : null;
            pane.getChildren().add(node != null ? node : chip(a, null));
        }
        return pane;
    }

    private static Node thumbnail(Attachment a) {
        Image image = image(a, THUMB_WIDTH, THUMB_HEIGHT);
        if (image == null) {
            return null;
        }
        ImageView view = new ImageView(image);
        view.setCursor(Cursor.HAND);
        Tooltip.install(view, new Tooltip(describe(a)));
        view.getStyleClass().add("attachment-thumb");
        view.setOnMouseClicked(e -> open(a, view));
        return view;
    }

    private static Image image(Attachment a, double width, double height) {
        try {
            Image image = new Image(new ByteArrayInputStream(a.bytes()), width, height, true, true);
            if (image.isError()) {
                LOG.warn("Can't preview image {}: {}", a.name(), image.getException() == null ? "unsupported format"
                        : image.getException().getMessage());
                return null;
            }
            return image;
        } catch (RuntimeException e) {
            LOG.warn("Can't preview image {}", a.name(), e);
            return null;
        }
    }

    private static String describe(Attachment a) {
        String sent = switch (a.kind()) {
            case TEXT, PDF -> "Text is sent to the model" + (a.truncated() ? " (cut to the configured limit)" : "");
            case IMAGE -> "Sent as an image (needs a vision model)";
            case AUDIO -> a.text() != null ? "The speech transcript is sent to the model (click to read it)"
                    : "The model only gets a note; it can't hear audio";
        };
        return a.name() + "\n" + a.mimeType() + ", " + Attachment.formatSize(a.size()) + "\n" + sent;
    }

    /** Opens an image or the extracted text / transcript in a separate window. */
    static void open(Attachment a, Node owner) {
        Window window = owner.getScene() == null ? null : owner.getScene().getWindow();
        Node content;
        if (a.kind() == Attachment.Kind.IMAGE) {
            Image image = image(a, 0, 0);
            if (image == null) {
                return;
            }
            ImageView view = new ImageView(image);
            view.setPreserveRatio(true);
            ScrollPane scroll = new ScrollPane(view);
            scroll.setPannable(true);
            content = scroll;
        } else {
            TextArea text = new TextArea(a.text() == null ? "" : a.text());
            text.setEditable(false);
            text.setWrapText(true);
            text.getStyleClass().add("mono");
            content = text;
        }
        Stage stage = new Stage();
        if (window != null) {
            stage.initOwner(window);
        }
        stage.setTitle(a.name());
        VBox root = new VBox(content);
        VBox.setVgrow(content, javafx.scene.layout.Priority.ALWAYS);
        Scene scene = new Scene(root, 800, 600);
        if (window != null && window.getScene() != null) {
            scene.getStylesheets().addAll(window.getScene().getStylesheets());
        }
        stage.setScene(scene);
        stage.show();
    }

    private static Button playButton(Attachment a) {
        Button button = new Button("▶");
        button.getStyleClass().add("attachment-remove");
        button.setFocusTraversable(false);
        button.setTooltip(new Tooltip("Play / stop"));
        button.setOnAction(e -> toggle(a, button));
        return button;
    }

    private static void toggle(Attachment a, Button button) {
        if (playing == button) {
            stop();
            return;
        }
        try {
            String ext = a.name().contains(".") ? a.name().substring(a.name().lastIndexOf('.')) : ".audio";
            Path file = Files.createTempFile("harness-audio-", ext.toLowerCase(Locale.ROOT));
            file.toFile().deleteOnExit();
            Files.write(file, a.bytes());
            toggleFile(file, a.name(), button);
        } catch (IOException | RuntimeException ex) {
            LOG.error("Can't play {}", a.name(), ex);
            button.setTooltip(new Tooltip("Can't play this format here: " + ex.getMessage()));
            stop();
        }
    }

    /** Plays a local audio file, or stops it if {@code button} is already playing; one clip plays at a time. */
    static void toggleFile(Path file, String name, Button button) {
        boolean wasThis = playing == button;
        stop();
        if (wasThis) {
            return;
        }
        try {
            player = new MediaPlayer(new Media(file.toUri().toString()));
            playing = button;
            button.setText("■");
            player.setOnEndOfMedia(AttachmentViews::stop);
            player.setOnError(() -> {
                LOG.error("Can't play {}: {}", name, player.getError() == null ? "" : player.getError().getMessage());
                button.setTooltip(new Tooltip("Can't play this format here"));
                stop();
            });
            player.play();
        } catch (RuntimeException ex) {
            LOG.error("Can't play {}", name, ex);
            button.setTooltip(new Tooltip("Can't play this format here: " + ex.getMessage()));
            stop();
        }
    }

    private static void stop() {
        if (player != null) {
            player.dispose();
            player = null;
        }
        if (playing != null) {
            playing.setText("▶");
            playing = null;
        }
    }
}
