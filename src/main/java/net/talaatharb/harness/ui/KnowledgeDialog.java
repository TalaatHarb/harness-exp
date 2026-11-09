package net.talaatharb.harness.ui;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javafx.application.Platform;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ChoiceDialog;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.SelectionMode;
import javafx.scene.control.Spinner;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.input.TransferMode;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.DirectoryChooser;
import javafx.stage.FileChooser;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;
import net.talaatharb.harness.attachments.AttachmentLoader;
import net.talaatharb.harness.history.ConversationSummary;
import net.talaatharb.harness.rag.RagDocument;
import net.talaatharb.harness.rag.RagService;
import net.talaatharb.harness.rag.RagService.Hit;
import net.talaatharb.harness.rag.RagService.SearchResult;
import net.talaatharb.harness.web.DocumentDeduplicator;
import net.talaatharb.harness.web.SiteCrawler;
import net.talaatharb.harness.web.WebResearchService;

/** Per-conversation document store ("📚"): add, inspect, search, copy and remove RAG documents. */
public class KnowledgeDialog {
    private static final Logger LOG = LoggerFactory.getLogger(KnowledgeDialog.class);
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
            .withZone(ZoneId.systemDefault());

    private final RagService rag;
    private final SiteCrawler crawler;
    private final String conversationId;
    private final Supplier<List<ConversationSummary>> otherConversations;
    private final ObservableList<RagDocument> documents = FXCollections.observableArrayList();
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "rag-ingest");
        t.setDaemon(true);
        return t;
    });

    private final Stage stage = new Stage();
    private final TableView<RagDocument> table = new TableView<>(documents);
    private final Label embedderLabel = new Label();
    private final Label statusLabel = new Label();
    private final ProgressBar progressBar = new ProgressBar(0);
    private final Button cancelButton = new Button("Cancel");
    private final VBox busyBox = new VBox(4);
    private final TextField searchField = new TextField();
    private final TextArea searchResults = new TextArea();
    private final List<Button> actions = new ArrayList<>();
    private Future<?> task;

    /**
     * @param otherConversations saved conversations offered as copy targets (the current one is filtered out)
     */
    public KnowledgeDialog(RagService rag, String conversationId, String title,
            Supplier<List<ConversationSummary>> otherConversations) {
        this(rag, null, conversationId, title, otherConversations);
    }

    /** @param crawler website crawler behind "Add URL…"; {@code null} disables it */
    public KnowledgeDialog(RagService rag, SiteCrawler crawler, String conversationId, String title,
            Supplier<List<ConversationSummary>> otherConversations) {
        this.rag = rag;
        this.crawler = crawler;
        this.conversationId = conversationId;
        this.otherConversations = otherConversations;
        stage.setTitle("Documents — " + (title == null || title.isBlank() ? "new conversation" : title));
        stage.setScene(new Scene(build(), 860, 600));
        stage.setOnHidden(e -> {
            cancel();
            worker.shutdownNow();
        });
        reload();
    }

    /** Shows the window modally; returns when it is closed. */
    public void showAndWait(Window owner) {
        if (owner != null) {
            stage.initOwner(owner);
            stage.getScene().getStylesheets().setAll(owner.getScene().getStylesheets());
        }
        stage.initModality(Modality.WINDOW_MODAL);
        stage.showAndWait();
    }

    Stage stage() {
        return stage;
    }

    private Region build() {
        Label help = new Label("Large material for this conversation only. Documents are split into passages and "
                + "embedded; the most relevant passages are added to the context, and the agent can search them "
                + "with rag_search. Drop files here to add them.");
        help.setWrapText(true);
        help.getStyleClass().add("section-help");

        TableColumn<RagDocument, String> name = column("Document", 260, d -> d.name);
        TableColumn<RagDocument, String> parts = column("Parts", 60, d -> String.valueOf(d.chunks));
        TableColumn<RagDocument, String> chars = column("Characters", 90, d -> String.format(Locale.ROOT, "%,d", d.chars));
        TableColumn<RagDocument, String> embedder = column("Embedder", 170, d -> d.embedder);
        TableColumn<RagDocument, String> added = column("Added", 130,
                d -> d.createdAt == null ? "" : TIME.format(d.createdAt));
        table.getColumns().setAll(List.of(name, parts, chars, embedder, added));
        table.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
        table.setPlaceholder(new Label("No documents yet: add files, a folder, text or a website."));
        table.setRowFactory(t -> {
            var row = new javafx.scene.control.TableRow<RagDocument>() {
                @Override
                protected void updateItem(RagDocument item, boolean empty) {
                    super.updateItem(item, empty);
                    setTooltip(empty || item == null ? null : new Tooltip(item.source));
                }
            };
            return row;
        });
        VBox.setVgrow(table, Priority.ALWAYS);

        Button addFiles = action("Add files…", this::onAddFiles);
        Button addFolder = action("Add folder…", this::onAddFolder);
        Button addText = action("Add text…", this::onAddText);
        Button addUrl = action("Add URL…", this::onAddUrl);
        addUrl.setTooltip(new Tooltip("Crawl a website (same host only, up to a link depth) and index its pages"));
        if (crawler == null) {
            addUrl.disableProperty().bind(javafx.beans.binding.Bindings.createBooleanBinding(() -> true));
        }
        Button delete = action("Delete", this::onDelete);
        Button copy = action("Copy to conversation…", this::onCopy);
        Button reindex = action("Re-index", this::onReindex);
        reindex.setTooltip(new Tooltip("Re-embed documents indexed with a different embedder than the current one"));
        delete.disableProperty().bind(table.getSelectionModel().selectedItemProperty().isNull());
        copy.setTooltip(new Tooltip("Copy the selected documents (or all, if none selected) to another conversation"));
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox buttons = new HBox(6, addFiles, addFolder, addText, addUrl, spacer, copy, reindex, delete);
        buttons.setAlignment(Pos.CENTER_LEFT);

        progressBar.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(progressBar, Priority.ALWAYS);
        cancelButton.setOnAction(e -> cancel());
        HBox progressRow = new HBox(6, progressBar, cancelButton);
        progressRow.setAlignment(Pos.CENTER_LEFT);
        statusLabel.setWrapText(true);
        busyBox.getChildren().setAll(progressRow);
        busyBox.setVisible(false);
        busyBox.managedProperty().bind(busyBox.visibleProperty());

        searchField.setPromptText("Try a search, e.g. a question about the documents (Enter)");
        searchField.setOnAction(e -> onSearch());
        Button search = action("Search", this::onSearch);
        HBox.setHgrow(searchField, Priority.ALWAYS);
        HBox searchRow = new HBox(6, searchField, search);
        searchResults.setEditable(false);
        searchResults.setWrapText(true);
        searchResults.setPrefRowCount(7);
        embedderLabel.getStyleClass().add("section-help");

        VBox root = new VBox(8, help, embedderLabel, table, buttons, busyBox, statusLabel, searchRow, searchResults);
        root.setPadding(new Insets(12));
        root.getStyleClass().add("section");
        root.setOnDragOver(e -> {
            if (e.getDragboard().hasFiles() && task == null) {
                e.acceptTransferModes(TransferMode.COPY);
            }
            e.consume();
        });
        root.setOnDragDropped(e -> {
            boolean ok = e.getDragboard().hasFiles();
            if (ok) {
                List<Path> paths = e.getDragboard().getFiles().stream().map(File::toPath).toList();
                ingest(expand(paths));
            }
            e.setDropCompleted(ok);
            e.consume();
        });
        return root;
    }

    private static TableColumn<RagDocument, String> column(String title, double width,
            java.util.function.Function<RagDocument, String> value) {
        TableColumn<RagDocument, String> c = new TableColumn<>(title);
        c.setPrefWidth(width);
        c.setCellValueFactory(cell -> new ReadOnlyObjectWrapper<>(value.apply(cell.getValue())));
        return c;
    }

    private Button action(String text, Runnable handler) {
        Button button = new Button(text);
        button.setOnAction(e -> handler.run());
        actions.add(button);
        return button;
    }

    private void reload() {
        try {
            List<RagDocument> docs = rag.documents(conversationId);
            documents.setAll(docs);
            String current = rag.embedder().id();
            long stale = docs.stream().filter(d -> !current.equals(d.embedder)).count();
            int parts = docs.stream().mapToInt(d -> d.chunks).sum();
            embedderLabel.setText(String.format("%d document(s), %d passage(s). Embedder: %s%s", docs.size(), parts,
                    current, stale > 0 ? " — " + stale + " document(s) use another embedder and are not searched "
                            + "until re-indexed" : ""));
        } catch (RuntimeException e) {
            LOG.error("Could not list documents of conversation {}", conversationId, e);
            statusLabel.setText("Could not list documents: " + e.getMessage());
        }
    }

    private void onAddFiles() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Add documents");
        chooser.getExtensionFilters().addAll(
                new FileChooser.ExtensionFilter("Text, markdown, code and PDF", java.util.stream.Stream
                        .concat(java.util.Arrays.stream(AttachmentLoader.TEXT_EXTENSIONS),
                                java.util.Arrays.stream(AttachmentLoader.PDF_EXTENSIONS))
                        .map(e -> "*." + e).toList()),
                new FileChooser.ExtensionFilter("All files", "*.*"));
        List<File> files = chooser.showOpenMultipleDialog(stage);
        if (files != null && !files.isEmpty()) {
            ingest(files.stream().map(File::toPath).toList());
        }
    }

    private void onAddFolder() {
        DirectoryChooser chooser = new DirectoryChooser();
        chooser.setTitle("Add a folder (text, markdown, code and PDF files, recursively)");
        File folder = chooser.showDialog(stage);
        if (folder != null) {
            ingest(expand(List.of(folder.toPath())));
        }
    }

    private List<Path> expand(List<Path> paths) {
        List<Path> files = new ArrayList<>();
        for (Path p : paths) {
            if (Files.isDirectory(p)) {
                try {
                    files.addAll(RagService.indexableFiles(p));
                } catch (Exception e) {
                    LOG.error("Could not list folder {}", p, e);
                    statusLabel.setText("Could not read " + p + ": " + e.getMessage());
                }
            } else {
                files.add(p);
            }
        }
        if (files.isEmpty() && !paths.isEmpty()) {
            statusLabel.setText("No text, markdown, code or PDF files found.");
        }
        return files;
    }

    private void onAddText() {
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.initOwner(stage);
        dialog.setTitle("Add text");
        TextField nameField = new TextField();
        nameField.setPromptText("Name, e.g. Meeting notes");
        TextArea text = new TextArea();
        text.setWrapText(true);
        text.setPrefSize(640, 360);
        GridPane grid = new GridPane();
        grid.setHgap(8);
        grid.setVgap(8);
        grid.addRow(0, new Label("Name"), nameField);
        grid.add(text, 0, 1, 2, 1);
        GridPane.setHgrow(nameField, Priority.ALWAYS);
        dialog.getDialogPane().setContent(grid);
        ButtonType add = new ButtonType("Add", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().setAll(add, ButtonType.CANCEL);
        dialog.getDialogPane().lookupButton(add).disableProperty().bind(text.textProperty().isEmpty());
        if (dialog.showAndWait().orElse(ButtonType.CANCEL) != add || text.getText().isBlank()) {
            return;
        }
        String name = nameField.getText().isBlank() ? "Pasted text" : nameField.getText().strip();
        String body = text.getText();
        run("Indexing " + name, progress -> {
            rag.ingestText(conversationId, name, "pasted text", body, progress);
            return "Added " + name + ".";
        });
    }

    private void onAddUrl() {
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.initOwner(stage);
        dialog.setTitle("Add URL");
        dialog.setHeaderText("Index a web page and the pages it links to on the same host.");
        TextField urlField = new TextField();
        urlField.setPromptText("https://example.com/docs/");
        urlField.setPrefColumnCount(40);
        Spinner<Integer> depth = new Spinner<>(0, SiteCrawler.MAX_DEPTH, SiteCrawler.DEFAULT_DEPTH);
        depth.setEditable(true);
        depth.setTooltip(new Tooltip("0 = only this page, 1 = plus the pages it links to, …"));
        Spinner<Integer> pages = new Spinner<>(1, SiteCrawler.MAX_PAGES, SiteCrawler.DEFAULT_PAGES);
        pages.setEditable(true);
        Label note = new Label("Links to other hosts are ignored. robots.txt, request pacing and the private-network "
                + "block from the web settings apply.");
        note.setWrapText(true);
        note.getStyleClass().add("section-help");
        GridPane grid = new GridPane();
        grid.setHgap(8);
        grid.setVgap(8);
        grid.addRow(0, new Label("URL"), urlField);
        grid.addRow(1, new Label("Max depth"), depth);
        grid.addRow(2, new Label("Max pages"), pages);
        grid.add(note, 0, 3, 2, 1);
        ColumnConstraints labels = new ColumnConstraints();
        labels.setMinWidth(Region.USE_PREF_SIZE);
        ColumnConstraints inputs = new ColumnConstraints();
        inputs.setMinWidth(0);
        inputs.setHgrow(Priority.ALWAYS);
        grid.getColumnConstraints().setAll(labels, inputs);
        urlField.setMinWidth(0);
        GridPane.setHgrow(urlField, Priority.ALWAYS);
        dialog.getDialogPane().setContent(grid);
        dialog.getDialogPane().setPrefWidth(560);
        ButtonType add = new ButtonType("Crawl and add", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().setAll(add, ButtonType.CANCEL);
        dialog.getDialogPane().lookupButton(add).disableProperty().bind(
                javafx.beans.binding.Bindings.createBooleanBinding(() -> urlField.getText().isBlank(),
                        urlField.textProperty()));
        Platform.runLater(urlField::requestFocus);
        if (dialog.showAndWait().orElse(ButtonType.CANCEL) != add) {
            return;
        }
        crawl(urlField.getText().strip(), spinnerValue(depth), spinnerValue(pages));
    }

    private static int spinnerValue(Spinner<Integer> spinner) {
        try {
            spinner.commitValue();
        } catch (RuntimeException e) {
            // invalid text: keep the last valid value
        }
        return spinner.getValue();
    }

    private void crawl(String url, int depth, int maxPages) {
        java.util.Set<String> indexed = new java.util.HashSet<>();
        for (RagDocument d : documents) {
            if (d.source != null && d.source.startsWith("http")) {
                indexed.add(DocumentDeduplicator.normalizeUrl(d.source));
            }
        }
        run("Crawling " + url, progress -> {
            List<String> added = new ArrayList<>();
            SiteCrawler.Result result = crawler.crawl(url, depth, maxPages, indexed, page -> {
                var doc = page.document();
                rag.ingestText(conversationId, doc.title().isBlank() ? doc.url() : doc.title(), doc.url(),
                        SiteCrawler.indexText(doc), null);
                added.add(doc.url());
                Platform.runLater(this::reload);
            }, progress::update);
            StringBuilder summary = new StringBuilder("Added ").append(result.accepted()).append(" page(s) from ")
                    .append(url).append('.');
            if (!result.skipped().isEmpty()) {
                summary.append(" Skipped ").append(result.skipped().size()).append(" (")
                        .append(reasons(result.skipped())).append(").");
            }
            if (!result.failures().isEmpty()) {
                summary.append(" Failed ").append(result.failures().size()).append(": ")
                        .append(reasons(result.failures()));
            }
            LOG.info("Crawled {} (depth {}, max {}): added {}, skipped {}, failed {}", url, depth, maxPages,
                    added.size(), result.skipped().size(), result.failures().size());
            return summary.toString();
        });
    }

    private static String reasons(List<WebResearchService.Skipped> items) {
        String text = String.join("; ", items.stream().limit(3).map(s -> s.url() + ": " + s.reason()).toList());
        return items.size() > 3 ? text + "; …" : text;
    }

    private void ingest(List<Path> files) {
        if (files.isEmpty()) {
            return;
        }
        run("Indexing " + files.size() + " file(s)", progress -> {
            int added = 0;
            List<String> failed = new ArrayList<>();
            for (int i = 0; i < files.size(); i++) {
                if (Thread.currentThread().isInterrupted()) {
                    break;
                }
                Path file = files.get(i);
                int index = i;
                try {
                    rag.ingestFile(conversationId, file, (message, fraction) -> progress.update(
                            "(" + (index + 1) + "/" + files.size() + ") " + file.getFileName() + ": " + message,
                            (index + Math.max(0, fraction)) / files.size()));
                    added++;
                } catch (java.io.InterruptedIOException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception e) {
                    if (Thread.currentThread().isInterrupted()) {
                        break;
                    }
                    LOG.error("Could not index {}", file, e);
                    failed.add(file.getFileName() + ": " + e.getMessage());
                }
                Platform.runLater(this::reload);
            }
            String summary = "Added " + added + " of " + files.size() + " file(s).";
            return failed.isEmpty() ? summary : summary + " Failed: " + String.join("; ", failed);
        });
    }

    private void onDelete() {
        List<RagDocument> selected = List.copyOf(table.getSelectionModel().getSelectedItems());
        if (selected.isEmpty() || !confirm("Delete " + selected.size() + " document(s) from this conversation?")) {
            return;
        }
        for (RagDocument doc : selected) {
            rag.delete(conversationId, doc.id);
        }
        statusLabel.setText("Deleted " + selected.size() + " document(s).");
        reload();
    }

    private void onCopy() {
        List<RagDocument> chosen = table.getSelectionModel().getSelectedItems().isEmpty() ? List.copyOf(documents)
                : List.copyOf(table.getSelectionModel().getSelectedItems());
        if (chosen.isEmpty()) {
            statusLabel.setText("Nothing to copy.");
            return;
        }
        List<ConversationSummary> targets;
        try {
            targets = otherConversations.get().stream().filter(c -> !c.id().equals(conversationId)).toList();
        } catch (RuntimeException e) {
            LOG.error("Could not list conversations", e);
            targets = List.of();
        }
        if (targets.isEmpty()) {
            statusLabel.setText("No other saved conversations to copy to.");
            return;
        }
        List<Target> options = targets.stream().map(Target::new).toList();
        ChoiceDialog<Target> dialog = new ChoiceDialog<>(options.get(0), options);
        dialog.initOwner(stage);
        dialog.setTitle("Copy documents");
        dialog.setHeaderText("Copy " + chosen.size() + " document(s) to:");
        dialog.showAndWait().ifPresent(target -> {
            try {
                List<RagDocument> copied = rag.copy(conversationId, chosen.stream().map(d -> d.id).toList(),
                        target.summary.id());
                statusLabel.setText("Copied " + copied.size() + " document(s) to \"" + target.summary.title() + "\".");
            } catch (Exception e) {
                LOG.error("Could not copy documents to {}", target.summary.id(), e);
                statusLabel.setText("Copy failed: " + e.getMessage());
            }
        });
    }

    private record Target(ConversationSummary summary) {
        @Override
        public String toString() {
            return summary.title() + (summary.updatedAt() == null ? "" : "  (" + TIME.format(summary.updatedAt()) + ")");
        }
    }

    private void onReindex() {
        run("Re-indexing", progress -> {
            int n = rag.reindex(conversationId, progress);
            return n == 0 ? "All documents already use " + rag.embedder().id() + "." : "Re-indexed " + n
                    + " document(s).";
        });
    }

    private void onSearch() {
        String query = searchField.getText() == null ? "" : searchField.getText().strip();
        if (query.isEmpty()) {
            return;
        }
        run("Searching", progress -> {
            var s = rag.settings();
            SearchResult result = rag.search(conversationId, query, s.maxSearchResults, s.minScorePercent / 100.0);
            StringBuilder out = new StringBuilder();
            for (Hit hit : result.hits()) {
                out.append(String.format(Locale.ROOT, "[%.2f] %s%n%s%n%n", hit.score(), RagService.label(hit),
                        hit.text().strip()));
            }
            if (result.hits().isEmpty()) {
                out.append(result.documents() == 0 && result.otherEmbedder().isEmpty() ? "No documents to search."
                        : "No passage above the minimum similarity.");
            }
            if (!result.otherEmbedder().isEmpty()) {
                out.append("\n(").append(result.otherEmbedder().size())
                        .append(" document(s) skipped: indexed with another embedder, use Re-index.)");
            }
            String text = out.toString().strip();
            Platform.runLater(() -> searchResults.setText(text));
            return result.hits().size() + " passage(s) found.";
        });
    }

    @FunctionalInterface
    private interface Job {
        String run(RagService.Progress progress) throws Exception;
    }

    /** Runs {@code job} on the worker thread with progress, cancel and error reporting. */
    private void run(String title, Job job) {
        if (task != null) {
            return;
        }
        setBusy(true);
        statusLabel.setText(title + "…");
        progressBar.setProgress(-1);
        task = worker.submit(() -> {
            String message;
            try {
                message = job.run((text, fraction) -> Platform.runLater(() -> {
                    statusLabel.setText(text);
                    progressBar.setProgress(fraction < 0 ? -1 : Math.min(1, fraction));
                }));
                if (Thread.currentThread().isInterrupted()) {
                    message = "Cancelled.";
                }
            } catch (java.io.InterruptedIOException | InterruptedException e) {
                message = "Cancelled.";
            } catch (Exception e) {
                LOG.error("{} failed", title, e);
                message = title + " failed: " + e.getMessage();
            }
            String done = message;
            Platform.runLater(() -> {
                task = null;
                setBusy(false);
                statusLabel.setText(done);
                reload();
            });
        });
    }

    private void cancel() {
        Future<?> running = task;
        if (running != null) {
            running.cancel(true);
            statusLabel.setText("Cancelling…");
        }
    }

    private void setBusy(boolean busy) {
        busyBox.setVisible(busy);
        actions.forEach(b -> {
            if (!b.disableProperty().isBound()) {
                b.setDisable(busy);
            }
        });
        table.setDisable(busy);
    }

    private boolean confirm(String message) {
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION, message, ButtonType.OK, ButtonType.CANCEL);
        alert.initOwner(stage);
        alert.setHeaderText(null);
        return alert.showAndWait().orElse(ButtonType.CANCEL) == ButtonType.OK;
    }
}
