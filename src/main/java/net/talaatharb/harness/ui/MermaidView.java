package net.talaatharb.harness.ui;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.concurrent.Worker;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.control.TitledPane;
import javafx.scene.layout.VBox;
import javafx.scene.web.WebView;
import javafx.util.Duration;

/** Renders untrusted Mermaid source using a bundled renderer in a network-restricted WebView. */
final class MermaidView {
    private static final Logger LOG = LoggerFactory.getLogger(MermaidView.class);
    private static final int MAX_SOURCE = 16_000;
    private static final String SCRIPT = "/META-INF/resources/webjars/mermaid/11.17.2/dist/mermaid.min.js";
    private static final String PAGE = """
            <!doctype html><html><head><meta charset="utf-8">
            <meta http-equiv="Content-Security-Policy"
              content="default-src 'none'; script-src 'none'; style-src 'unsafe-inline';
              img-src 'none'; connect-src 'none'; font-src 'none'; frame-src 'none';
              object-src 'none'; base-uri 'none'; form-action 'none'">
            <style>body{margin:0} svg{max-width:100%;height:auto}</style></head>
            <body><div id="diagram"></div></body></html>
            """;

    private MermaidView() {
    }

    static Node create(String source) {
        Label status = new Label("Rendering Mermaid diagram...");
        TextArea text = new TextArea(source);
        text.setEditable(false);
        text.setWrapText(false);
        text.setPrefRowCount(Math.max(2, Math.min(12, source.split("\n", -1).length)));
        TitledPane raw = new TitledPane("Mermaid source", text);
        raw.setExpanded(false);
        VBox result = new VBox(4, status, raw);
        if (source.length() > MAX_SOURCE || source.contains("%%{") || source.stripLeading().startsWith("---")) {
            status.setText("Diagram not rendered: source is too large or contains configuration directives.");
            raw.setExpanded(true);
            return result;
        }
        String script;
        try (var in = MermaidView.class.getResourceAsStream(SCRIPT)) {
            if (in == null) {
                throw new IOException("Bundled Mermaid library not found");
            }
            script = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            LOG.error("Could not load Mermaid", e);
            status.setText("Mermaid renderer unavailable.");
            raw.setExpanded(true);
            return result;
        }

        WebView view = new WebView();
        result.getProperties().put("mermaidWebView", view);
        view.setContextMenuEnabled(false);
        view.setPrefWidth(ChatMarkdownView.MAX_IMAGE_WIDTH);
        view.setPrefHeight(100);
        view.setMaxWidth(ChatMarkdownView.MAX_IMAGE_WIDTH);
        view.getEngine().setOnAlert(e -> LOG.warn("Blocked Mermaid alert"));
        view.getEngine().getLoadWorker().stateProperty().addListener((obs, previous, state) -> {
            if (state == Worker.State.FAILED || state == Worker.State.CANCELLED) {
                status.setText("Could not load Mermaid renderer.");
                raw.setExpanded(true);
                result.getProperties().remove("mermaidWebView");
            } else if (state == Worker.State.SUCCEEDED) {
                try {
                    view.getEngine().executeScript(script);
                    String encoded = Base64.getEncoder().encodeToString(source.getBytes(StandardCharsets.UTF_8));
                    view.getEngine().executeScript("""
                            window.diagramStatus = 'pending';
                            document.addEventListener('click', function(e) { e.preventDefault(); }, true);
                            mermaid.initialize({startOnLoad:false, securityLevel:'strict',
                                flowchart:{htmlLabels:false}, maxTextSize:16000});
                            var bytes = Uint8Array.from(atob('%s'), function(c) { return c.charCodeAt(0); });
                            var source = new TextDecoder('utf-8').decode(bytes);
                            mermaid.render('diagram-svg', source).then(function(result) {
                                document.getElementById('diagram').innerHTML = result.svg;
                                window.diagramStatus = 'ok';
                            }).catch(function() { window.diagramStatus = 'error'; });
                            """.formatted(encoded));
                    Timeline poll = new Timeline();
                    poll.getKeyFrames().add(new KeyFrame(Duration.millis(200), event -> {
                        try {
                            Object stateValue = view.getEngine().executeScript("window.diagramStatus");
                            if ("ok".equals(stateValue)) {
                                double height = ((Number) view.getEngine().executeScript(
                                        "Math.ceil(document.documentElement.scrollHeight)")).doubleValue();
                                view.setPrefHeight(Math.max(80, Math.min(800, height + 10)));
                                result.getChildren().setAll(view, raw);
                                result.getProperties().remove("mermaidWebView");
                                poll.stop();
                            } else if ("error".equals(stateValue)) {
                                status.setText("Could not render Mermaid diagram.");
                                raw.setExpanded(true);
                                result.getProperties().remove("mermaidWebView");
                                poll.stop();
                            }
                        } catch (RuntimeException e) {
                            LOG.warn("Mermaid rendering failed", e);
                            status.setText("Could not render Mermaid diagram.");
                            raw.setExpanded(true);
                            result.getProperties().remove("mermaidWebView");
                            poll.stop();
                        }
                    }));
                    poll.setCycleCount(50);
                    poll.setOnFinished(event -> {
                        status.setText("Mermaid rendering timed out.");
                        raw.setExpanded(true);
                        result.getProperties().remove("mermaidWebView");
                    });
                    poll.play();
                } catch (RuntimeException e) {
                    LOG.warn("Mermaid rendering failed", e);
                    status.setText("Could not initialize Mermaid renderer.");
                    raw.setExpanded(true);
                    result.getProperties().remove("mermaidWebView");
                }
            }
        });
        view.getEngine().loadContent(PAGE);
        return result;
    }
}
