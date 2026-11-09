package net.talaatharb.harness.ui;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Function;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javafx.beans.property.BooleanProperty;
import javafx.beans.value.ChangeListener;
import javafx.beans.value.WeakChangeListener;
import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.Tooltip;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import net.talaatharb.harness.reply.MarkdownText;
import one.jpro.platform.mdfx.MarkdownView;

/**
 * Markdown bubble for model replies. Images are not fetched until the user allows them (a remote image URL can
 * leak data), and links only open after confirmation.
 */
class ChatMarkdownView extends MarkdownView {
    private static final Logger LOG = LoggerFactory.getLogger(ChatMarkdownView.class);
    static final double MAX_IMAGE_WIDTH = 640;

    private BooleanProperty imagesAllowed;
    private Consumer<String> linkHandler;
    private Function<String, Optional<Path>> localImages;

    ChatMarkdownView(String text, BooleanProperty imagesAllowed, Consumer<String> linkHandler) {
        this(text, imagesAllowed, linkHandler, null);
    }

    /**
     * @param imagesAllowed shared "allow all images" switch; placeholders load their image when it turns true
     * @param linkHandler   called with the target when a link is clicked
     * @param localImages   resolves references to the conversation's own (local, trusted) files; may be null
     */
    ChatMarkdownView(String text, BooleanProperty imagesAllowed, Consumer<String> linkHandler,
            Function<String, Optional<Path>> localImages) {
        super("", List.of());
        this.imagesAllowed = imagesAllowed;
        this.linkHandler = linkHandler;
        this.localImages = localImages;
        getStyleClass().addAll("bubble", "bubble-assistant", "markdown");
        setMaxWidth(720);
        MenuItem copy = new MenuItem("Copy");
        copy.setOnAction(e -> ChatBubbles.copyToClipboard(text));
        ContextMenu menu = new ContextMenu(copy);
        setOnContextMenuRequested(e -> menu.show(this, e.getScreenX(), e.getScreenY()));
        setMdString(MarkdownText.prepare(text));
    }

    @Override
    public Node generateImage(String url) {
        String tex = MarkdownText.mathSource(url);
        if (tex != null) {
            return LatexRenderer.render(tex, url.startsWith(MarkdownText.DISPLAY_MATH));
        }
        if (imagesAllowed == null) {
            return new Label();
        }
        if (localImages != null && !url.contains(":") && !url.contains("//")) {
            Optional<Path> local = localImages.apply(url);
            if (local.isPresent() && ArtifactPreview.kind(local.get().getFileName().toString())
                    == ArtifactPreview.Kind.IMAGE) {
                Node preview = ArtifactPreview.image(local.get(), local.get().getFileName().toString());
                if (preview != null) {
                    return preview;
                }
            }
        }
        StackPane holder = new StackPane();
        holder.setAlignment(Pos.CENTER_LEFT);
        holder.getStyleClass().add("markdown-image");
        if (imagesAllowed.get()) {
            holder.getChildren().setAll(image(url));
            return holder;
        }
        Label label = new Label("🖼 Image not loaded: " + shorten(url));
        label.setTooltip(new Tooltip(url));
        Button load = new Button("Load image");
        load.getStyleClass().add("copy-button");
        load.setOnAction(e -> holder.getChildren().setAll(image(url)));
        Button allowAll = new Button("Allow all images");
        allowAll.getStyleClass().add("copy-button");
        allowAll.setTooltip(new Tooltip("Load images in replies automatically (Harness → Reply & UI)"));
        allowAll.setOnAction(e -> imagesAllowed.set(true));
        HBox placeholder = new HBox(6, label, load, allowAll);
        placeholder.setAlignment(Pos.CENTER_LEFT);
        placeholder.getStyleClass().add("image-placeholder");
        holder.getChildren().setAll(placeholder);
        ChangeListener<Boolean> listener = (obs, was, allowed) -> {
            if (allowed && holder.getChildren().contains(placeholder)) {
                holder.getChildren().setAll(image(url));
            }
        };
        // The holder keeps the listener alive; the weak wrapper lets closed chats be collected.
        holder.getProperties().put("imagesAllowedListener", listener);
        imagesAllowed.addListener(new WeakChangeListener<>(listener));
        return holder;
    }

    private static Node image(String url) {
        if (!MarkdownText.isLoadableImage(url)) {
            return new Label("🖼 Unsupported image source: " + shorten(url));
        }
        try {
            Image image = new Image(url, true);
            ImageView view = new ImageView(image);
            view.setPreserveRatio(true);
            image.widthProperty().addListener((obs, old, width) ->
                    view.setFitWidth(Math.min(width.doubleValue(), MAX_IMAGE_WIDTH)));
            Label failed = new Label("🖼 Image failed to load: " + shorten(url));
            StackPane pane = new StackPane(view);
            pane.setAlignment(Pos.CENTER_LEFT);
            image.errorProperty().addListener((obs, old, error) -> {
                if (error) {
                    LOG.warn("Could not load image {}", url, image.getException());
                    pane.getChildren().setAll(failed);
                }
            });
            return pane;
        } catch (RuntimeException e) {
            LOG.warn("Could not load image {}", url, e);
            return new Label("🖼 Image failed to load: " + shorten(url));
        }
    }

    @Override
    public void setLink(Node node, String link, String description) {
        if (link == null || link.isBlank()) {
            return;
        }
        node.setCursor(Cursor.HAND);
        node.getStyleClass().add("markdown-link");
        Tooltip.install(node, new Tooltip(link));
        node.setOnMouseClicked(e -> {
            if (e.getButton() == MouseButton.PRIMARY && linkHandler != null) {
                linkHandler.accept(link);
            }
        });
    }

    static String shorten(String url) {
        String value = url == null ? "" : url;
        return value.length() > 80 ? value.substring(0, 77) + "…" : value;
    }
}
