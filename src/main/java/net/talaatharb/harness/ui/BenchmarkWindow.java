package net.talaatharb.harness.ui;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.SplitPane;
import javafx.scene.control.TextArea;
import javafx.scene.control.TitledPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.DirectoryChooser;
import javafx.stage.Stage;
import javafx.stage.Window;
import net.talaatharb.harness.benchmark.BenchmarkReport;
import net.talaatharb.harness.guardrails.SecretRedactionGuardrail;

/** Simple read-only viewer; reports never execute generated UI or tool actions. */
public final class BenchmarkWindow {
    private static final Logger LOG = LoggerFactory.getLogger(BenchmarkWindow.class);
    private final ListView<BenchmarkReport> runs = new ListView<>();
    private final ListView<BenchmarkReport.Turn> turns = new ListView<>();
    private final Label summary = new Label("Select a benchmark run");
    private final Label status = new Label();
    private final Label folderLabel = new Label();
    private final TextArea prompt = area("prompt");
    private final TextArea response = area("response");
    private final TextArea assessment = area("assessment");
    private Path folder;

    public BenchmarkWindow(Path folder) {
        this.folder = folder.toAbsolutePath();
    }

    public void show(Window owner) {
        Stage stage = new Stage();
        stage.initOwner(owner);
        stage.setTitle("Benchmark runs");
        stage.setScene(new Scene(content(), 1100, 750));
        UiSupport.applyStyles(stage.getScene());
        stage.show();
    }

    VBox content() {
        Button refresh = new Button("Refresh");
        refresh.setOnAction(e -> refresh());
        Button choose = new Button("Choose folder...");
        choose.setOnAction(e -> {
            DirectoryChooser chooser = new DirectoryChooser();
            chooser.setTitle("Benchmark runs folder");
            if (Files.isDirectory(folder)) chooser.setInitialDirectory(folder.toFile());
            var selected = chooser.showDialog(choose.getScene().getWindow());
            if (selected != null) {
                folder = selected.toPath();
                refresh();
            }
        });
        runs.setId("benchmarkRuns");
        runs.setPlaceholder(new Label("No run reports in this folder"));
        runs.setCellFactory(list -> new ListCell<>() {
            @Override protected void updateItem(BenchmarkReport report, boolean empty) {
                super.updateItem(report, empty);
                setText(empty || report == null ? null : SecretRedactionGuardrail.redact(
                        report.file().getFileName().toString()) + "\n" + report.model());
            }
        });
        turns.setId("benchmarkTurns");
        turns.setCellFactory(list -> new ListCell<>() {
            @Override protected void updateItem(BenchmarkReport.Turn turn, boolean empty) {
                super.updateItem(turn, empty);
                setText(empty || turn == null ? null : turn.caseId() + " | "
                        + (turn.failures().isEmpty() ? "checks passed" : "FAILED")
                        + (turn.review().isBlank() ? "" : " | review")
                        + "\n" + turn.prompt().replace('\n', ' '));
            }
        });
        runs.getSelectionModel().selectedItemProperty().addListener((o, old, selected) -> {
            turns.getItems().clear();
            clearTurn();
            summary.setText(selected == null ? "Select a benchmark run"
                    : SecretRedactionGuardrail.redact(selected.file().getFileName().toString()) + " | " + selected.model() + "\n"
                            + selected.conclusion() + "\n" + selected.suite() + " | " + selected.date());
            if (selected != null) {
                turns.getItems().setAll(selected.turns());
                turns.getSelectionModel().selectFirst();
            }
        });
        turns.getSelectionModel().selectedItemProperty().addListener((o, old, selected) -> {
            clearTurn();
            if (selected != null) {
                prompt.setText(selected.prompt());
                response.setText(selected.response());
                assessment.setText(selected.model() + " | " + selected.caseId() + "\n" + selected.assessment());
            }
        });
        summary.setWrapText(true);
        summary.setId("benchmarkSummary");
        status.setWrapText(true);
        VBox detail = new VBox(8, pane("Prompt", prompt), pane("Response", response),
                pane("Assessment", assessment));
        SplitPane split = new SplitPane(runs, turns, detail);
        split.setDividerPositions(0.23, 0.48);
        VBox.setVgrow(split, Priority.ALWAYS);
        VBox root = new VBox(8, new HBox(8, choose, refresh), folderLabel, summary, split, status);
        root.setPadding(new Insets(12));
        refresh();
        return root;
    }

    private void refresh() {
        Path selected = runs.getSelectionModel().getSelectedItem() == null ? null
                : runs.getSelectionModel().getSelectedItem().file();
        runs.getItems().clear();
        summary.setText("Select a benchmark run");
        folderLabel.setText(SecretRedactionGuardrail.redact(folder.toString()));
        if (!Files.isDirectory(folder)) {
            status.setText("Folder does not exist yet. Run a benchmark or choose another folder.");
            return;
        }
        int invalid = 0;
        try (var files = Files.list(folder)) {
            for (Path file : files.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().toLowerCase(java.util.Locale.ROOT).endsWith(".json"))
                    .sorted(Comparator.reverseOrder()).toList()) {
                try {
                    runs.getItems().add(BenchmarkReport.read(file));
                } catch (IllegalArgumentException notReport) {
                    // Settings exports and suites share this folder.
                } catch (IOException e) {
                    invalid++;
                    LOG.warn("Cannot read benchmark file {}: {}", SecretRedactionGuardrail.redact(file.toString()),
                            SecretRedactionGuardrail.redact(e.getMessage()));
                }
            }
            status.setText(runs.getItems().size() + " reports; settings exports and suites omitted."
                    + (invalid == 0 ? "" : " " + invalid + " unreadable files (see log)."));
            runs.getItems().stream().filter(r -> r.file().equals(selected)).findFirst()
                    .ifPresentOrElse(r -> runs.getSelectionModel().select(r),
                            () -> runs.getSelectionModel().selectFirst());
        } catch (IOException e) {
            LOG.warn("Cannot list benchmark folder: {}", SecretRedactionGuardrail.redact(e.getMessage()));
            status.setText("Cannot read folder: " + SecretRedactionGuardrail.redact(e.getMessage()));
        }
    }

    private void clearTurn() {
        prompt.clear();
        response.clear();
        assessment.clear();
    }

    private static TextArea area(String id) {
        TextArea area = new TextArea();
        area.setId(id);
        area.setEditable(false);
        area.setWrapText(true);
        return area;
    }

    private static TitledPane pane(String title, TextArea content) {
        TitledPane pane = new TitledPane(title, content);
        pane.setCollapsible(false);
        VBox.setVgrow(pane, Priority.ALWAYS);
        return pane;
    }
}
