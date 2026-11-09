package net.talaatharb.harness.ui;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.DatePicker;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.TitledPane;
import net.talaatharb.harness.agent.AgentRunResult;
import net.talaatharb.harness.llm.ChatMessage;
import net.talaatharb.harness.reply.DynamicUiRenderer;
import net.talaatharb.harness.reply.ReplySettings;
import net.talaatharb.harness.reply.UiSchemaValidator;
import net.talaatharb.harness.tools.ToolCall;
import net.talaatharb.harness.tools.ToolResult;

class UiSmokeTest {
    @org.junit.jupiter.api.io.TempDir java.nio.file.Path temp;

    @BeforeAll
    static void startToolkit() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(
                !System.getProperty("os.name").toLowerCase(java.util.Locale.ROOT).contains("linux")
                        || System.getenv("DISPLAY") != null,
                "Run JavaFX smoke tests under xvfb-run on headless Linux");
        CountDownLatch ready = new CountDownLatch(1);
        try {
            Platform.startup(() -> {
                Platform.setImplicitExit(false);
                ready.countDown();
            });
        } catch (IllegalStateException alreadyStarted) {
            ready.countDown();
        } catch (UnsupportedOperationException unavailable) {
            org.junit.jupiter.api.Assumptions.assumeTrue(false, "JavaFX display unavailable: " + unavailable);
        }
        assertTrue(ready.await(15, TimeUnit.SECONDS), "JavaFX startup timed out");
    }

    @Test
    void benchmarkViewerShowsRunConclusionAndSelectedTurn() throws Exception {
        java.nio.file.Files.copy(java.nio.file.Path.of("benchmark", "qwen2.5-3b-run2.json"),
                temp.resolve("run.json"));
        java.nio.file.Files.writeString(temp.resolve("settings.json"), "{\"settings\":{}}");
        fx(() -> {
            Parent root = new BenchmarkWindow(temp).content();
            new javafx.scene.Scene(root, 1100, 750);
            root.applyCss();
            root.layout();
            var runs = (javafx.scene.control.ListView<?>) root.lookup("#benchmarkRuns");
            var turns = (javafx.scene.control.ListView<?>) root.lookup("#benchmarkTurns");
            assertEquals(1, runs.getItems().size());
            assertEquals(34, turns.getItems().size());
            String summary = ((Label) root.lookup("#benchmarkSummary")).getText();
            assertTrue(summary.contains("qwen2.5:3b"));
            assertTrue(summary.contains("run.json"));
            assertTrue(summary.contains("30/34"));
            assertTrue(((TextArea) root.lookup("#prompt")).getText().contains("hash map"));
            assertFalse(((TextArea) root.lookup("#response")).getText().isBlank());
            assertTrue(((TextArea) root.lookup("#assessment")).getText().contains("Manual review required"));
            turns.getSelectionModel().select(1);
            assertTrue(((TextArea) root.lookup("#prompt")).getText().contains("packing"));
        });
    }

    @Test
    void decisionWindowHasDistinctLayouts() throws Exception {
        fx(() -> {
            var answers = new ArrayList<net.talaatharb.harness.interaction.DecisionResponse>();
            Parent binary = DecisionView.build(new net.talaatharb.harness.interaction.DecisionRequest(
                    "Proceed?", List.of("Yes", "No"), null), answers::add);
            assertNotNull(binary.lookup("#decisionBinary"));
            assertNull(binary.lookup("#decisionInput"));
            ((Button) binary.lookup("#decisionOption1")).fire();
            assertEquals("No", answers.get(0).choice());

            Parent input = DecisionView.build(new net.talaatharb.harness.interaction.DecisionRequest(
                    "Name?", List.of(), "Your name"), answers::add);
            assertNull(input.lookup("#decisionBinary"));
            ((TextArea) input.lookup("#decisionInput")).setText("Ada");
            ((Button) input.lookup("#decisionSubmit")).fire();
            assertEquals("Ada", answers.get(1).input());
            assertNull(answers.get(1).choice());

            Parent choices = DecisionView.build(new net.talaatharb.harness.interaction.DecisionRequest(
                    "Which?", List.of("a", "b", "c"), "Note"), answers::add);
            assertNotNull(choices.lookup("#decisionChoices"));
            ((TextField) choices.lookup("#decisionInput")).setText("n");
            ((Button) choices.lookup("#decisionSubmit")).fire();
            assertEquals("a", answers.get(2).choice());
            assertEquals("n", answers.get(2).input());
        });
    }

    @Test
    void allSettingsSectionsLoadAndNewControlsApply() throws Exception {
        var injector = com.google.inject.Guice.createInjector(
                new net.talaatharb.harness.config.AppModule(temp.resolve("settings.json")));
        try {
            fx(() -> {
                javafx.fxml.FXMLLoader loader = new javafx.fxml.FXMLLoader(
                        SettingsController.class.getResource("settings.fxml"));
                loader.setControllerFactory(injector::getInstance);
                Parent root;
                try {
                    root = loader.load();
                } catch (java.io.IOException e) {
                    throw new java.io.UncheckedIOException(e);
                }
                Parent memoryRoot = (Parent) loader.getNamespace().get("memorySection");
                Parent replyRoot = (Parent) loader.getNamespace().get("replySection");
                var direct = (javafx.scene.control.CheckBox) memoryRoot.lookup("#directRememberCheck");
                assertNotNull(direct);
                assertTrue(direct.isSelected());
                direct.setSelected(false);
                var context = (javafx.scene.control.CheckBox) replyRoot.lookup("#contextCheck");
                context.setSelected(true);
                var timeline = (javafx.scene.control.CheckBox) replyRoot.lookup("#timelineCheck");
                assertTrue(timeline.isSelected());
                timeline.setSelected(false);
                Parent contextRoot = (Parent) loader.getNamespace().get("contextSection");
                var compaction = (javafx.scene.control.CheckBox) contextRoot.lookup("#compactionCheck");
                assertTrue(compaction.isSelected());
                compaction.setSelected(false);
                nodes(root).stream().filter(n -> n instanceof Button b && b.getText().equals("Apply"))
                        .map(Button.class::cast).findFirst().orElseThrow().fire();
                var settings = injector.getInstance(net.talaatharb.harness.config.SettingsStore.class).current();
                assertFalse(settings.memory.directRememberRequests);
                assertTrue(settings.reply.showContext);
                assertFalse(settings.reply.showTimeline);
                assertFalse(settings.context.compactionEnabled);
                assertEquals(6, settings.context.compactionKeepRecent);
            });
        } finally {
            injector.getInstance(net.talaatharb.harness.tools.mcp.McpClientManager.class).close();
            injector.getInstance(net.talaatharb.harness.memory.MemoryService.class).close();
            injector.getInstance(net.talaatharb.harness.rag.RagService.class).close();
        }
    }

    @Test
    void editsValidatesAndSubmitsOnlyItsOwnForm() throws Exception {
        fx(() -> {
            List<String> sent = new ArrayList<>();
            String first = """
                    {"type":"form","action":"Search","props":{"submitLabel":"Search now"},"children":[
                    {"type":"input","props":{"name":"name","required":true}},
                    {"type":"input","props":{"name":"date","inputType":"date","required":true}},
                    {"type":"input","props":{"name":"guests","inputType":"number","required":true}}]}
                    """;
            String second = """
                    {"type":"form","action":"Other","children":[
                    {"type":"input","props":{"name":"unrelated","required":true}}]}
                    """;
            Node root = DynamicUiRenderer.render(UiSchemaValidator.validateJson(
                    "{\"type\":\"column\",\"children\":[" + first + "," + second + "]}"), sent::add);
            Button submit = nodes(root).stream().filter(n -> n instanceof Button b && b.getText().equals("Search now"))
                    .map(Button.class::cast).findFirst().orElseThrow();
            submit.fire();
            assertTrue(sent.isEmpty());
            ((TextField) byId(root, "form-input-name")).setText("Ada");
            DatePicker date = (DatePicker) byId(root, "form-input-date");
            date.getEditor().setText("2026-02-30");
            ((TextField) byId(root, "form-input-guests")).setText("NaN");
            submit.fire();
            assertTrue(sent.isEmpty());
            date.getEditor().setText("2026-11-04");
            ((TextField) byId(root, "form-input-guests")).setText("2");
            submit.fire();
            assertEquals(1, sent.size());
            assertTrue(sent.get(0).contains("\"name\":\"Ada\""));
            assertTrue(sent.get(0).contains("\"date\":\"2026-11-04\""));
            assertTrue(sent.get(0).contains("\"guests\":\"2\""));
            assertFalse(sent.get(0).contains("unrelated"));
            Button oldButton = (Button) DynamicUiRenderer.render(
                    UiSchemaValidator.validateJson("{\"type\":\"button\",\"text\":\"Old\",\"action\":\"still works\"}"), sent::add);
            oldButton.fire();
            assertEquals("still works", sent.get(1));
        });
    }

    @Test
    void preservesInterruptedTextAndSeparateStatusOnReplay() throws Exception {
        fx(() -> {
            AssistantTurnView live = new AssistantTurnView(new ReplySettings(), s -> {}, null);
            live.appendToken("Partial ");
            live.appendToken("answer");
            live.finish(new AgentRunResult(AgentRunResult.Status.CANCELLED, null, null, 1, 0));
            assertTrue(labels(live.root()).contains("Partial answer"));
            assertTrue(labels(live.root()).contains("Cancelled"));
            AssistantTurnView replay = new AssistantTurnView(new ReplySettings(), s -> {}, null);
            replay.finish(new AgentRunResult(AgentRunResult.Status.CANCELLED, "Saved partial", "Stopped", 1, 0));
            assertTrue(labels(replay.root()).contains("Saved partial"));
            assertTrue(labels(replay.root()).contains("Cancelled: Stopped"));
        });
    }

    @Test
    void downloadableFilesAppearLiveAndWhenReplayingWithoutToolTrace() throws Exception {
        var directory = java.nio.file.Files.createTempDirectory("harness-artifact-ui");
        var artifacts = new net.talaatharb.harness.history.ArtifactStore(directory.resolve("history.db"));
        var artifact = artifacts.write("conversation", "story.md", "# Story\nOnce upon a time.");
        String output = net.talaatharb.harness.tools.builtin.WriteFileTool.ARTIFACT_PREFIX
                + new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(artifact);
        var binary = artifacts.writeBinary("conversation", "image.png", new byte[] { 0, (byte) 255 });
        String binaryOutput = net.talaatharb.harness.history.ArtifactStore.ARTIFACT_PREFIX
                + new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(binary);
        var audio = artifacts.writeBinary("conversation", "speech.wav", new byte[] { 1, 2 });
        String audioOutput = net.talaatharb.harness.history.ArtifactStore.ARTIFACT_PREFIX
                + new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(audio);
        try {
            fx(() -> {
                ReplySettings settings = new ReplySettings();
                settings.showToolTrace = false;
                AssistantTurnView live = new AssistantTurnView(settings, s -> {}, null);
                live.setArtifacts(artifacts, "conversation");
                live.toolResult(new ToolCall("write_file", Map.of()), net.talaatharb.harness.tools.ToolResult.ok(output));
                live.toolResult(new ToolCall("write_binary_file", Map.of()),
                        net.talaatharb.harness.tools.ToolResult.ok(binaryOutput));
                assertTrue(labels(live.root()).contains("File · story.md"));
                assertTrue(labels(live.root()).contains("File · image.png"));
                assertEquals(2, nodes(live.root()).stream().filter(Button.class::isInstance)
                        .filter(b -> b.getStyleClass().contains("artifact-save")).count());
                assertTrue(nodes(live.root()).stream().filter(TitledPane.class::isInstance)
                        .anyMatch(p -> ((TitledPane) p).getText().equals("Preview · story.md")));
                assertEquals(2, nodes(live.root()).stream()
                        .filter(n -> n.getStyleClass().contains("artifact-preview")).count());
                live.toolResult(new ToolCall("synthesize", Map.of()),
                        net.talaatharb.harness.tools.ToolResult.ok(audioOutput));
                assertEquals(1, nodes(live.root()).stream()
                        .filter(n -> n.getStyleClass().contains("artifact-play")).count());
                live.finish(new AgentRunResult(AgentRunResult.Status.COMPLETED,
                        "Here it is: ![generated](image.png) and ![missing](other.png)", null, 1, 1));
                assertEquals(3, nodes(live.root()).stream()
                        .filter(n -> n.getStyleClass().contains("artifact-preview")).count());
                AssistantTurnView replay = new AssistantTurnView(settings, s -> {}, null);
                replay.setArtifacts(artifacts, "conversation");
                replay.replay(ChatMessage.tool("generate_media",
                        "Result of tool `generate_media` (success):\nCreated files\n" + output + "\n" + binaryOutput));
                assertTrue(labels(replay.root()).contains("File · story.md"));
                assertTrue(labels(replay.root()).contains("File · image.png"));
                AssistantTurnView failed = new AssistantTurnView(settings, s -> {}, null);
                failed.setArtifacts(artifacts, "conversation");
                failed.toolResult(new ToolCall("write_file", Map.of()), net.talaatharb.harness.tools.ToolResult.error(output));
                assertFalse(labels(failed.root()).contains("File · story.md"));
            });
        } finally {
            artifacts.deleteConversation("conversation");
            java.nio.file.Files.delete(directory.resolve("history.db.artifacts"));
            java.nio.file.Files.delete(directory);
        }
    }

    @Test
    void outcomesPersistWithTraceOffIncludingFullSavedMemory() throws Exception {
        fx(() -> {
            ReplySettings settings = new ReplySettings();
            settings.showToolTrace = false;
            AssistantTurnView view = new AssistantTurnView(settings, s -> {}, null);
            String exact = "Saved memory: " + "Exact contents\n".repeat(30);
            view.toolResult(new ToolCall("remember", Map.of()), ToolResult.ok(exact));
            view.toolResult(new ToolCall("write_file", Map.of()),
                    ToolResult.error("The user denied this tool call. Do not retry it; continue without it."));
            view.replay(ChatMessage.tool("calculate", "calculate (error)\nDivision by zero"));
            view.finish(new AgentRunResult(AgentRunResult.Status.COMPLETED, "Everything succeeded", null, 1, 3));
            List<TitledPane> panes = view.root().getChildren().stream().filter(TitledPane.class::isInstance)
                    .map(TitledPane.class::cast).toList();
            assertEquals(3, panes.size());
            assertTrue(panes.get(0).getText().contains("Succeeded"));
            assertTrue(panes.get(0).isExpanded());
            assertEquals(exact, ((Label) panes.get(0).getContent()).getText());
            assertTrue(panes.get(1).getText().contains("Denied"));
            assertTrue(panes.get(2).getText().contains("Failed"));
            assertTrue(panes.get(1).getText().contains("action not completed"));
            assertTrue(panes.get(2).getText().contains("action not completed"));
            AssistantTurnView replay = new AssistantTurnView(settings, s -> {}, null);
            replay.replay(ChatMessage.tool("write_file",
                    "Result of tool `write_file` (error):\nThe user denied this tool call. Do not retry it."));
            replay.finish(new AgentRunResult(AgentRunResult.Status.COMPLETED, "Everything succeeded", null, 1, 1));
            TitledPane replayOutcome = (TitledPane) replay.root().getChildren().get(0);
            assertTrue(replayOutcome.getText().contains("Denied"));
            assertTrue(replayOutcome.getText().contains("action not completed"));
        });
    }

    @Test
    void timelineIsShownCollapsedUnderTheReplyAndCanBeHidden() throws Exception {
        fx(() -> {
            net.talaatharb.harness.trace.RunTimeline timeline = new net.talaatharb.harness.trace.RunTimeline("m");
            timeline.tool("calculate", 0, true, "4");
            timeline.end("COMPLETED", null);
            ReplySettings settings = new ReplySettings();
            AssistantTurnView view = new AssistantTurnView(settings, s -> {}, null);
            view.setTimeline(timeline);
            view.finish(new AgentRunResult(AgentRunResult.Status.COMPLETED, "Four", null, 1, 1));
            TitledPane pane = view.root().getChildren().stream().filter(TitledPane.class::isInstance)
                    .map(TitledPane.class::cast).filter(p -> p.getText().startsWith("Timeline · "))
                    .findFirst().orElseThrow();
            assertFalse(pane.isExpanded());
            assertTrue(((TextArea) pane.getContent()).getText().contains("calculate"));
            assertTrue(view.root().getChildren().indexOf(pane) > 0, "shown after the reply");
            settings.showTimeline = false;
            AssistantTurnView hidden = new AssistantTurnView(settings, s -> {}, null);
            hidden.setTimeline(timeline);
            hidden.finish(new AgentRunResult(AgentRunResult.Status.COMPLETED, "Four", null, 1, 1));
            assertTrue(hidden.root().getChildren().stream().noneMatch(TitledPane.class::isInstance));
        });
    }

    @Test
    void contextInspectorIsExplicitReadOnlyAndKeepsSourceMarkers() throws Exception {
        fx(() -> {
            List<ChatMessage> context = List.of(ChatMessage.system(
                    "## Memory\nSaved fact\n## Knowledge: source.md\n[truncated]\nExcerpt"));
            ReplySettings settings = new ReplySettings();
            AssistantTurnView hidden = new AssistantTurnView(settings, s -> {}, null);
            hidden.showContext(context);
            assertEquals(1, hidden.root().getChildren().size());
            settings.showContext = true;
            AssistantTurnView shown = new AssistantTurnView(settings, s -> {}, null);
            shown.showContext(context);
            TitledPane pane = (TitledPane) shown.root().getChildren().get(0);
            assertFalse(pane.isExpanded());
            TextArea text = (TextArea) pane.getContent();
            assertFalse(text.isEditable());
            assertTrue(text.getText().contains("## Memory"));
            assertTrue(text.getText().contains("source.md"));
            assertTrue(text.getText().contains("[truncated]"));
        });
    }

    static final String MARKDOWN_REPLY = "# Title\nLine one\nLine two\n\n- **bold** item\n- second\n\n"
            + "| A | B |\n|---|---|\n| 1 | 2 |\n\n```java\nint x = 1;\n```\n\n"
            + "![logo](https://example.invalid/x.png)\n\nSee [the site](https://example.com) and <b>tag</b>, 3 < 4.";

    @Test
    void repliesRenderAsMarkdownWithRawToggleBlockedImagesAndConfirmedLinks() throws Exception {
        fx(() -> {
            javafx.beans.property.BooleanProperty images = new javafx.beans.property.SimpleBooleanProperty(false);
            AssistantTurnView view = new AssistantTurnView(new ReplySettings(), s -> {}, null, images);
            view.finish(new AgentRunResult(AgentRunResult.Status.COMPLETED, MARKDOWN_REPLY, null, 1, 0));
            ChatMarkdownView markdown = view.root().getChildren().stream().filter(ChatMarkdownView.class::isInstance)
                    .map(ChatMarkdownView.class::cast).findFirst().orElseThrow();
            String text = texts(markdown);
            assertTrue(text.contains("Title"), text);
            assertFalse(text.contains("**"), text);
            assertTrue(text.replace("|", "").contains("int x = 1;"), text);
            assertTrue(text.contains("Line one|\n|Line two"), "single line breaks are kept: " + text);
            assertTrue(text.contains("3 < 4"), text);

            ChatMarkdownView math = new ChatMarkdownView("Solve $a(x)y'' + b(x)y' + c(x)y = g(x)$ and $\\nosuchcmd{x}$",
                    new javafx.beans.property.SimpleBooleanProperty(), s -> {});
            assertEquals(1, nodes(math).stream().filter(n -> n.getStyleClass().contains("math-inline")).count());
            assertEquals(1, nodes(math).stream().filter(n -> n.getStyleClass().contains("math-error")).count());

            Node placeholder = nodes(markdown).stream()
                    .filter(n -> n.getStyleClass().contains("image-placeholder")).findFirst().orElseThrow();
            assertTrue(labels(placeholder).stream().anyMatch(l -> l.contains("Image not loaded")));
            Button allowAll = nodes(placeholder).stream().filter(Button.class::isInstance).map(Button.class::cast)
                    .filter(b -> b.getText().equals("Allow all images")).findFirst().orElseThrow();
            allowAll.fire();
            assertTrue(images.get());
            assertTrue(nodes(markdown).stream().noneMatch(n -> n.getStyleClass().contains("image-placeholder")));

            Button raw = nodes(view.root()).stream().filter(Button.class::isInstance).map(Button.class::cast)
                    .filter(b -> b.getText().equals("Raw")).findFirst().orElseThrow();
            raw.fire();
            assertTrue(labels(view.root()).contains(MARKDOWN_REPLY));
            assertFalse(view.root().getChildren().contains(markdown));
            raw.fire();
            assertTrue(view.root().getChildren().contains(markdown));

            List<String> opened = new ArrayList<>();
            ChatMarkdownView direct = new ChatMarkdownView("[the site](https://example.com)",
                    new javafx.beans.property.SimpleBooleanProperty(), opened::add);
            Node link = nodes(direct).stream().filter(n -> n.getStyleClass().contains("markdown-link"))
                    .findFirst().orElseThrow();
            link.getOnMouseClicked().handle(new javafx.scene.input.MouseEvent(
                    javafx.scene.input.MouseEvent.MOUSE_CLICKED, 0, 0, 0, 0, javafx.scene.input.MouseButton.PRIMARY,
                    1, false, false, false, false, true, false, false, false, false, false, null));
            assertEquals(List.of("https://example.com"), opened);

            ReplySettings plain = new ReplySettings();
            plain.renderMarkdown = false;
            AssistantTurnView rawView = new AssistantTurnView(plain, s -> {}, null);
            rawView.finish(new AgentRunResult(AgentRunResult.Status.COMPLETED, MARKDOWN_REPLY, null, 1, 0));
            assertTrue(labels(rawView.root()).contains(MARKDOWN_REPLY));
            assertTrue(nodes(rawView.root()).stream().noneMatch(ChatMarkdownView.class::isInstance));
            assertTrue(nodes(rawView.root()).stream().filter(Button.class::isInstance)
                    .noneMatch(b -> ((Button) b).getText().equals("Raw")));
        });
    }

    private static String texts(Node root) {
        StringBuilder out = new StringBuilder();
        for (Node n : nodes(root)) {
            if (n instanceof javafx.scene.text.Text t) {
                out.append(t.getText()).append('|');
            } else if (n instanceof Label l) {
                out.append(l.getText()).append('|');
            }
        }
        return out.toString();
    }

    private static void fx(Runnable assertion) throws Exception {
        FutureTask<Void> task = new FutureTask<>(assertion, null);
        Platform.runLater(task);
        task.get(20, TimeUnit.SECONDS);
    }

    private static Node byId(Node root, String id) {
        return nodes(root).stream().filter(n -> id.equals(n.getId())).findFirst().orElseThrow();
    }

    private static List<String> labels(Node root) {
        return nodes(root).stream().filter(Label.class::isInstance).map(Label.class::cast).map(Label::getText).toList();
    }

    private static List<Node> nodes(Node root) {
        List<Node> nodes = new ArrayList<>();
        nodes.add(root);
        if (root instanceof Parent parent) {
            parent.getChildrenUnmodifiable().forEach(child -> nodes.addAll(nodes(child)));
        }
        return nodes;
    }
}
