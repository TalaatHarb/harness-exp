package net.talaatharb.harness.ui;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Locale;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.concurrent.Worker;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Label;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.web.WebView;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import javafx.stage.Window;
import javafx.util.Duration;

/** Shows a rendered Mermaid diagram in its own window with zoom and PNG/SVG export. */
final class MermaidWindow {
    private static final Logger LOG = LoggerFactory.getLogger(MermaidWindow.class);
    private static final double MIN_ZOOM = 0.25;
    private static final double MAX_ZOOM = 4;
    private static final double PNG_SCALE = 2;
    private static final double MAX_PNG_SIDE = 8_192;
    /** Blank page for rasterizing; only data: images are allowed, everything else is blocked. */
    private static final String EXPORT_PAGE = """
            <!doctype html><html><head><meta charset="utf-8">
            <meta http-equiv="Content-Security-Policy"
              content="default-src 'none'; script-src 'none'; img-src data:; connect-src 'none';
              font-src 'none'; frame-src 'none'; object-src 'none'; base-uri 'none'; form-action 'none'">
            </head><body></body></html>
            """;

    private MermaidWindow() {
    }

    /** A rendered diagram: sanitized SVG markup plus its natural size in CSS pixels. */
    record Diagram(String svg, double width, double height) {
        Diagram {
            width = Math.max(1, width);
            height = Math.max(1, height);
        }
    }

    static Stage open(Diagram diagram, Node owner) {
        Window window = owner == null || owner.getScene() == null ? null : owner.getScene().getWindow();
        WebView view = new WebView();
        view.setContextMenuEnabled(false);
        view.getEngine().loadContent(page(diagram, 16));

        Label zoomLabel = new Label("100%");
        Button zoomOut = new Button("−");
        Button zoomIn = new Button("+");
        Button reset = new Button("100%");
        Runnable refresh = () -> zoomLabel.setText(Math.round(view.getZoom() * 100) + "%");
        zoomOut.setOnAction(e -> {
            view.setZoom(Math.max(MIN_ZOOM, view.getZoom() / 1.25));
            refresh.run();
        });
        zoomIn.setOnAction(e -> {
            view.setZoom(Math.min(MAX_ZOOM, view.getZoom() * 1.25));
            refresh.run();
        });
        reset.setOnAction(e -> {
            view.setZoom(1);
            refresh.run();
        });
        Button png = new Button("Save PNG…");
        png.setId("mermaidSavePng");
        Button svg = new Button("Save SVG…");
        svg.setId("mermaidSaveSvg");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox bar = new HBox(6, zoomOut, zoomLabel, zoomIn, reset, spacer, png, svg);
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.setPadding(new Insets(6));

        Stage stage = new Stage();
        if (window != null) {
            stage.initOwner(window);
        }
        stage.setTitle("Mermaid diagram");
        svg.setOnAction(e -> {
            File file = choose(stage, "diagram.svg", "SVG image", "*.svg");
            if (file != null) {
                try {
                    writeSvg(diagram, file.toPath());
                } catch (IOException | RuntimeException ex) {
                    error(stage, ex);
                }
            }
        });
        png.setOnAction(e -> {
            File file = choose(stage, "diagram.png", "PNG image", "*.png");
            if (file != null) {
                png.setDisable(true);
                renderPng(diagram, PNG_SCALE, bytes -> {
                    png.setDisable(false);
                    try {
                        Files.write(file.toPath(), bytes);
                    } catch (IOException | RuntimeException ex) {
                        error(stage, ex);
                    }
                }, ex -> {
                    png.setDisable(false);
                    error(stage, ex);
                });
            }
        });

        BorderPane root = new BorderPane(view);
        root.setTop(bar);
        Scene scene = new Scene(root,
                Math.min(1_200, Math.max(500, diagram.width() + 60)),
                Math.min(900, Math.max(350, diagram.height() + 90)));
        if (window != null && window.getScene() != null) {
            scene.getStylesheets().addAll(window.getScene().getStylesheets());
        }
        stage.setScene(scene);
        stage.show();
        return stage;
    }

    /** Rasterizes the diagram at {@code scale} in WebKit and hands the PNG bytes to {@code done} on the FX thread. */
    static void renderPng(Diagram diagram, double scale, Consumer<byte[]> done, Consumer<Throwable> failed) {
        double effective = Math.min(scale,
                MAX_PNG_SIDE / Math.max(diagram.width(), diagram.height()));
        int width = (int) Math.ceil(diagram.width() * effective);
        int height = (int) Math.ceil(diagram.height() * effective);
        WebView web = new WebView();
        web.getEngine().getLoadWorker().stateProperty().addListener((obs, old, state) -> {
            if (state == Worker.State.SUCCEEDED) {
                try {
                    String svg = Base64.getEncoder().encodeToString(diagram.svg().getBytes(StandardCharsets.UTF_8));
                    web.getEngine().executeScript("""
                            window.pngStatus = 'pending';
                            var img = new Image();
                            img.onload = function() {
                                try {
                                    var c = document.createElement('canvas');
                                    c.width = %1$d; c.height = %2$d;
                                    var g = c.getContext('2d');
                                    g.fillStyle = '#ffffff';
                                    g.fillRect(0, 0, %1$d, %2$d);
                                    g.drawImage(img, 0, 0, %1$d, %2$d);
                                    window.pngData = c.toDataURL('image/png');
                                    window.pngStatus = 'ok';
                                } catch (e) { window.pngStatus = 'error'; }
                            };
                            img.onerror = function() { window.pngStatus = 'error'; };
                            img.src = 'data:image/svg+xml;base64,%3$s';
                            """.formatted(width, height, svg));
                } catch (RuntimeException ex) {
                    failed.accept(ex);
                    return;
                }
                Timeline poll = new Timeline();
                poll.getKeyFrames().add(new KeyFrame(Duration.millis(100), e -> {
                    Object status = web.getEngine().executeScript("window.pngStatus");
                    if ("ok".equals(status)) {
                        poll.stop();
                        String data = (String) web.getEngine().executeScript("window.pngData");
                        int comma = data.indexOf(',');
                        if (!data.startsWith("data:image/png;base64,") || comma < 0) {
                            failed.accept(new IOException("Renderer did not produce a PNG"));
                        } else {
                            done.accept(Base64.getDecoder().decode(data.substring(comma + 1)));
                        }
                    } else if ("error".equals(status)) {
                        poll.stop();
                        failed.accept(new IOException("Could not rasterize diagram"));
                    }
                }));
                poll.setCycleCount(100);
                poll.setOnFinished(e -> failed.accept(new IOException("PNG export timed out")));
                poll.play();
            } else if (state == Worker.State.FAILED || state == Worker.State.CANCELLED) {
                failed.accept(new IOException("Could not load diagram renderer"));
            }
        });
        web.getEngine().loadContent(EXPORT_PAGE);
    }

    static void writeSvg(Diagram diagram, Path file) throws IOException {
        String svg = diagram.svg();
        if (!svg.startsWith("<?xml")) {
            svg = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" + svg;
        }
        Files.writeString(file, svg, StandardCharsets.UTF_8);
    }

    /** Static page with scripts disabled; the SVG was already sanitized by Mermaid's strict mode. */
    private static String page(Diagram diagram, int margin) {
        return """
                <!doctype html><html><head><meta charset="utf-8">
                <meta http-equiv="Content-Security-Policy"
                  content="default-src 'none'; script-src 'none'; style-src 'unsafe-inline';
                  img-src 'none'; connect-src 'none'; font-src 'none'; frame-src 'none';
                  object-src 'none'; base-uri 'none'; form-action 'none'">
                <style>html,body{margin:0;background:#fff} body{padding:%dpx}
                svg{display:block;max-width:none !important;width:%spx !important;height:%spx !important}</style>
                </head><body>%s</body></html>
                """.formatted(margin, number(diagram.width()), number(diagram.height()), diagram.svg());
    }

    private static String number(double value) {
        return String.format(Locale.ROOT, "%.2f", value);
    }

    private static File choose(Window owner, String name, String description, String pattern) {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Export diagram");
        chooser.setInitialFileName(name);
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter(description, pattern));
        return chooser.showSaveDialog(owner);
    }

    private static void error(Window owner, Throwable ex) {
        LOG.error("Could not export Mermaid diagram", ex);
        Alert alert = new Alert(Alert.AlertType.ERROR, String.valueOf(ex.getMessage()), ButtonType.OK);
        alert.setHeaderText("Export failed");
        alert.initOwner(owner);
        alert.show();
    }
}
