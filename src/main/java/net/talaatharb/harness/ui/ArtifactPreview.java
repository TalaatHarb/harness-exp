package net.talaatharb.harness.ui;

import java.io.IOException;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Set;

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
import javafx.scene.control.TitledPane;
import javafx.scene.control.Tooltip;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;
import javafx.stage.Window;

/** Inline previews of conversation artifacts, read from their managed local copy. */
final class ArtifactPreview {
    private static final Logger LOG = LoggerFactory.getLogger(ArtifactPreview.class);
    static final double MAX_WIDTH = 480;
    static final double MAX_HEIGHT = 360;
    static final long MAX_TEXT_BYTES = 256 * 1024;

    enum Kind { IMAGE, AUDIO, TEXT, NONE }

    private static final Set<String> IMAGES = Set.of("png", "jpg", "jpeg", "gif", "bmp");
    private static final Set<String> AUDIO = Set.of("wav", "mp3", "m4a", "aac", "aif", "aiff");
    private static final Set<String> TEXT = Set.of("txt", "md", "markdown", "csv", "tsv", "json", "xml", "html",
            "htm", "yaml", "yml", "log", "ini", "toml", "properties", "java", "py", "js", "ts", "css", "sql", "sh",
            "ps1", "bat", "c", "h", "cpp", "go", "rs", "kt", "svg");

    private ArtifactPreview() {
    }

    static Kind kind(String name) {
        int dot = name.lastIndexOf('.');
        String ext = dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
        if (IMAGES.contains(ext)) {
            return Kind.IMAGE;
        }
        if (AUDIO.contains(ext)) {
            return Kind.AUDIO;
        }
        return TEXT.contains(ext) ? Kind.TEXT : Kind.NONE;
    }

    /** A play/stop button for audio artifacts, or {@code null} for other kinds. */
    static Button playButton(Path file, String name) {
        if (kind(name) != Kind.AUDIO) {
            return null;
        }
        Button button = new Button("▶");
        button.getStyleClass().addAll("copy-button", "artifact-play");
        button.setFocusTraversable(false);
        button.setTooltip(new Tooltip("Play / stop " + name));
        button.setOnAction(e -> AttachmentViews.toggleFile(file, name, button));
        return button;
    }

    /** The preview shown under the file row (image thumbnail or collapsed text), or {@code null}. */
    static Node preview(Path file, String name) {
        return switch (kind(name)) {
            case IMAGE -> image(file, name);
            case TEXT -> text(file, name);
            default -> null;
        };
    }

    /** A local image scaled to fit the chat; clicking it opens the full-size image. */
    static Node image(Path file, String name) {
        try {
            Image image = new Image(file.toUri().toString(), MAX_WIDTH, MAX_HEIGHT, true, true, true);
            ImageView view = new ImageView(image);
            view.setPreserveRatio(true);
            view.setCursor(Cursor.HAND);
            view.getStyleClass().add("artifact-image");
            Tooltip.install(view, new Tooltip(name + " · click to open full size"));
            view.setOnMouseClicked(e -> openImage(file, name, view));
            StackPane pane = new StackPane(view);
            pane.setAlignment(Pos.CENTER_LEFT);
            pane.getStyleClass().add("artifact-preview");
            image.errorProperty().addListener((obs, old, error) -> {
                if (error) {
                    LOG.warn("Can't preview image {}", name, image.getException());
                    pane.getChildren().setAll(new Label("🖼 Can't preview " + name + "; use Save as…"));
                }
            });
            return pane;
        } catch (RuntimeException e) {
            LOG.warn("Can't preview image {}", name, e);
            return null;
        }
    }

    private static Node text(Path file, String name) {
        try {
            long size = Files.size(file);
            if (size > MAX_TEXT_BYTES) {
                return null;
            }
            String content = StandardCharsets.UTF_8.newDecoder().decode(
                    java.nio.ByteBuffer.wrap(Files.readAllBytes(file))).toString();
            TextArea area = new TextArea(content);
            area.setEditable(false);
            area.setWrapText(true);
            area.setPrefRowCount((int) Math.min(16, Math.max(3, content.lines().count() + 1)));
            area.getStyleClass().add("mono");
            TitledPane pane = new TitledPane("Preview · " + name, area);
            pane.setExpanded(false);
            pane.getStyleClass().add("artifact-preview");
            return pane;
        } catch (CharacterCodingException e) {
            return null;
        } catch (IOException | RuntimeException e) {
            LOG.warn("Can't preview {}", name, e);
            return null;
        }
    }

    private static void openImage(Path file, String name, Node owner) {
        Window window = owner.getScene() == null ? null : owner.getScene().getWindow();
        ImageView view = new ImageView(new Image(file.toUri().toString(), true));
        view.setPreserveRatio(true);
        ScrollPane scroll = new ScrollPane(view);
        scroll.setPannable(true);
        Stage stage = new Stage();
        if (window != null) {
            stage.initOwner(window);
        }
        stage.setTitle(name);
        stage.setScene(new Scene(scroll, 800, 600));
        stage.show();
    }
}
