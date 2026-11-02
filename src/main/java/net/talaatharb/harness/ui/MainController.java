package net.talaatharb.harness.ui;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.inject.Inject;

import javafx.application.Platform;
import javafx.beans.binding.Bindings;
import javafx.beans.property.IntegerProperty;
import javafx.beans.property.SimpleIntegerProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.collections.transformation.FilteredList;
import javafx.fxml.FXML;
import javafx.scene.Node;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.MenuItem;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.TextFormatter;
import javafx.scene.control.TextInputDialog;
import javafx.scene.control.ToggleButton;
import javafx.scene.input.Clipboard;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.TransferMode;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import net.talaatharb.harness.attachments.AttachmentLoader;
import net.talaatharb.harness.agent.AgentListener;
import net.talaatharb.harness.agent.AgentLoop;
import net.talaatharb.harness.agent.AgentRunResult;
import net.talaatharb.harness.agent.Conversation;
import net.talaatharb.harness.config.HarnessSettings;
import net.talaatharb.harness.config.SettingsStore;
import net.talaatharb.harness.context.ContextSettings;
import net.talaatharb.harness.history.ConversationRecord;
import net.talaatharb.harness.history.ConversationStore;
import net.talaatharb.harness.history.ConversationSummary;
import net.talaatharb.harness.history.TurnRecord;
import net.talaatharb.harness.llm.Attachment;
import net.talaatharb.harness.llm.ChatMessage;
import net.talaatharb.harness.llm.Role;
import net.talaatharb.harness.llm.ModelProviderRegistry;
import net.talaatharb.harness.tools.ToolCall;
import net.talaatharb.harness.tools.ToolResult;

/**
 * ChatGPT-like main window: a model picker, the conversation and a composer, plus a collapsible history sidebar.
 * Everything else lives in settings.
 */
public class MainController {
    private static final Logger LOG = LoggerFactory.getLogger(MainController.class);
    private static final DateTimeFormatter HISTORY_TIME = DateTimeFormatter.ofPattern("MMM d, HH:mm", Locale.ROOT)
            .withZone(ZoneId.systemDefault());

    private final AgentLoop agentLoop;
    private final SettingsStore store;
    private final ModelProviderRegistry providers;
    private final SettingsWindow settingsWindow;
    private final ConversationStore history;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "agent-loop");
        t.setDaemon(true);
        return t;
    });
    private final ObservableList<ConversationSummary> historyItems = FXCollections.observableArrayList();
    private final FilteredList<ConversationSummary> filteredHistory = new FilteredList<>(historyItems);

    /** The open chat. Replaced (not cleared) on switch so a still-finishing run keeps writing to its own chat. */
    private ConversationRecord record = ConversationRecord.create();
    private Conversation conversation = new Conversation();
    private final List<Exchange> exchanges = new ArrayList<>();

    /** Files attached in the composer for the next message. */
    private final ObservableList<Attachment> pending = FXCollections.observableArrayList();
    private final IntegerProperty loadingAttachments = new SimpleIntegerProperty();
    private Future<?> running;
    private AtomicBoolean cancelFlag;

    @FXML private ComboBox<String> modelCombo;
    @FXML private Label statusLabel;
    @FXML private ScrollPane scrollPane;
    @FXML private VBox messagesBox;
    @FXML private Label emptyLabel;
    @FXML private BorderPane root;
    @FXML private VBox composer;
    @FXML private FlowPane attachmentsBar;
    @FXML private Button attachButton;
    @FXML private TextArea inputArea;
    @FXML private Button sendButton;
    @FXML private Button stopButton;
    @FXML private ToggleButton historyToggle;
    @FXML private VBox historyPane;
    @FXML private TextField historySearch;
    @FXML private ListView<ConversationSummary> historyList;
    @FXML private Button deleteConversationButton;
    @FXML private Button renameConversationButton;
    @FXML private Label historyStatus;

    /** UI + model state of one request/response pair. */
    private static final class Exchange {
        final TurnRecord turn;
        final Node userNode;
        final List<Attachment> attachments;
        AssistantTurnView view;

        Exchange(TurnRecord turn, List<Attachment> attachments) {
            this.turn = turn;
            this.attachments = List.copyOf(attachments);
            this.userNode = ChatBubbles.user(turn.userText, this.attachments);
        }
    }

    @Inject
    public MainController(AgentLoop agentLoop, SettingsStore store, ModelProviderRegistry providers,
            SettingsWindow settingsWindow, ConversationStore history) {
        this.agentLoop = agentLoop;
        this.store = store;
        this.providers = providers;
        this.settingsWindow = settingsWindow;
        this.history = history;
    }

    @FXML
    private void initialize() {
        modelCombo.setValue(store.current().model.model);
        modelCombo.setOnAction(e -> onModelChosen());
        inputArea.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.ENTER && !e.isShiftDown()) {
                e.consume();
                onSend();
            }
        });
        messagesBox.heightProperty().addListener((obs, o, n) -> scrollPane.setVvalue(1.0));
        store.addListener(() -> Platform.runLater(() -> {
            String model = store.current().model.model;
            if (!model.equals(modelCombo.getValue())) {
                modelCombo.setValue(model);
            }
        }));
        initHistory();
        initAttachments();
        onRefreshModels();
    }

    private void initHistory() {
        historyPane.managedProperty().bind(historyPane.visibleProperty());
        historyPane.visibleProperty().bind(historyToggle.selectedProperty());
        historyList.setItems(filteredHistory);
        historyList.setPlaceholder(new Label("No saved conversations"));
        historyList.setCellFactory(list -> {
            ListCell<ConversationSummary> cell = new ListCell<>() {
                @Override
                protected void updateItem(ConversationSummary item, boolean empty) {
                    super.updateItem(item, empty);
                    if (empty || item == null) {
                        setText(null);
                        setGraphic(null);
                        return;
                    }
                    Label title = new Label(item.title());
                    title.getStyleClass().add("history-title");
                    Label meta = new Label((item.updatedAt() == null ? "" : HISTORY_TIME.format(item.updatedAt()))
                            + " · " + item.turns() + (item.turns() == 1 ? " message" : " messages"));
                    meta.getStyleClass().add("history-meta");
                    setText(null);
                    setGraphic(new VBox(1, title, meta));
                }
            };
            MenuItem rename = new MenuItem("Rename…");
            rename.setOnAction(e -> onRenameConversation());
            MenuItem delete = new MenuItem("Delete…");
            delete.setOnAction(e -> onDeleteConversation());
            cell.emptyProperty().addListener((obs, wasEmpty, isEmpty) ->
                    cell.setContextMenu(isEmpty ? null : new ContextMenu(rename, delete)));
            cell.setOnMouseClicked(e -> {
                if (!cell.isEmpty() && e.getButton() == MouseButton.PRIMARY && e.getClickCount() == 2) {
                    onRenameConversation();
                }
            });
            return cell;
        });
        historyList.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.F2) {
                e.consume();
                onRenameConversation();
            }
        });
        historyList.getSelectionModel().selectedItemProperty().addListener((obs, o, selected) -> {
            deleteConversationButton.setDisable(selected == null);
            renameConversationButton.setDisable(selected == null);
            if (selected != null && !selected.id().equals(record.id)) {
                openConversation(selected.id());
            }
        });
        historySearch.textProperty().addListener((obs, o, query) -> {
            String q = query == null ? "" : query.strip().toLowerCase(Locale.ROOT);
            filteredHistory.setPredicate(s -> q.isEmpty() || s.title().toLowerCase(Locale.ROOT).contains(q));
        });
        deleteConversationButton.setDisable(true);
        renameConversationButton.setDisable(true);
        historyStatus.setVisible(!history.persistent());
        historyStatus.setManaged(!history.persistent());
        refreshHistory();
    }

    private void refreshHistory() {
        try {
            historyItems.setAll(history.list());
        } catch (RuntimeException e) {
            LOG.error("Could not list conversation history", e);
        }
        selectCurrentInHistory();
    }

    private void selectCurrentInHistory() {
        for (ConversationSummary item : filteredHistory) {
            if (item.id().equals(record.id)) {
                historyList.getSelectionModel().select(item);
                return;
            }
        }
        historyList.getSelectionModel().clearSelection();
    }

    @FXML
    private void onRefreshModels() {
        statusLabel.setText("connecting…");
        java.util.concurrent.CompletableFuture.runAsync(() -> {
            try {
                HarnessSettings settings = store.current();
                List<String> models = providers.get(settings.model).listModels(settings.model);
                Platform.runLater(() -> {
                    String current = modelCombo.getValue();
                    modelCombo.setItems(FXCollections.observableArrayList(models));
                    modelCombo.setValue(current);
                    statusLabel.setText("● " + models.size() + " models");
                    statusLabel.getStyleClass().setAll("status", "status-ok");
                });
            } catch (Exception e) {
                LOG.error("Could not list models", e);
                Platform.runLater(() -> {
                    statusLabel.setText("● offline");
                    statusLabel.getStyleClass().setAll("status", "status-error");
                    statusLabel.setTooltip(new javafx.scene.control.Tooltip(e.getMessage()));
                });
            }
        });
    }

    private void onModelChosen() {
        String model = modelCombo.getValue();
        if (model == null || model.isBlank() || model.equals(store.current().model.model)) {
            return;
        }
        HarnessSettings updated = store.copy();
        updated.model.model = model.trim();
        store.replace(updated);
    }

    @FXML
    private void onNewChat() {
        onStop();
        showConversation(ConversationRecord.create());
    }

    @FXML
    private void onOpenSettings() {
        settingsWindow.show(messagesBox.getScene().getWindow());
    }

    @FXML
    private void onRenameConversation() {
        ConversationSummary selected = historyList.getSelectionModel().getSelectedItem();
        if (selected == null) {
            return;
        }
        TextInputDialog dialog = new TextInputDialog(selected.title());
        dialog.initOwner(messagesBox.getScene().getWindow());
        dialog.setTitle("Rename conversation");
        dialog.setHeaderText("New name for \"" + selected.title() + "\"");
        TextField field = dialog.getEditor();
        field.setPrefColumnCount(40);
        field.setTextFormatter(new TextFormatter<String>(change ->
                change.getControlNewText().length() > ConversationRecord.MAX_CUSTOM_TITLE_CHARS ? null : change));
        Node ok = dialog.getDialogPane().lookupButton(ButtonType.OK);
        ok.disableProperty().bind(Bindings.createBooleanBinding(
                () -> field.getText() == null || field.getText().isBlank(), field.textProperty()));
        Platform.runLater(field::selectAll);
        dialog.showAndWait().ifPresent(title -> rename(selected.id(), title));
    }

    private void rename(String id, String title) {
        try {
            String stored = history.rename(id, title);
            if (id.equals(record.id)) {
                // Keep the open record in sync, or the next save would restore the old title.
                record.title = stored;
            }
            LOG.info("Renamed conversation {} to '{}'", id, stored);
        } catch (RuntimeException e) {
            LOG.error("Could not rename conversation {}", id, e);
            Alert error = new Alert(Alert.AlertType.ERROR, e.getMessage(), ButtonType.OK);
            error.initOwner(messagesBox.getScene().getWindow());
            error.setHeaderText("Rename failed");
            error.showAndWait();
        }
        refreshHistory();
    }

    @FXML
    private void onDeleteConversation() {
        ConversationSummary selected = historyList.getSelectionModel().getSelectedItem();
        if (selected == null) {
            return;
        }
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
                "Delete \"" + selected.title() + "\"? This cannot be undone.", ButtonType.OK, ButtonType.CANCEL);
        confirm.initOwner(messagesBox.getScene().getWindow());
        confirm.setHeaderText("Delete conversation");
        if (confirm.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) {
            return;
        }
        if (selected.id().equals(record.id)) {
            onNewChat();
        }
        try {
            history.delete(selected.id());
        } catch (RuntimeException e) {
            LOG.error("Could not delete conversation {}", selected.id(), e);
        }
        refreshHistory();
    }

    private void openConversation(String id) {
        onStop();
        history.load(id).ifPresentOrElse(this::showConversation, () -> {
            LOG.warn("Conversation {} could not be loaded", id);
            refreshHistory();
        });
    }

    /** Switches the view to {@code rec}, rebuilding bubbles and tool traces from the stored turns. */
    private void showConversation(ConversationRecord rec) {
        record = rec;
        conversation = new Conversation();
        conversation.replaceAll(rec.messages);
        exchanges.clear();
        messagesBox.getChildren().clear();
        HarnessSettings settings = store.current();
        for (int i = 0; i < rec.turns.size(); i++) {
            TurnRecord turn = rec.turns.get(i);
            int end = i + 1 < rec.turns.size() ? rec.turns.get(i + 1).messageStart : rec.messages.size();
            Exchange exchange = addExchange(turn, settings, attachmentsOf(rec, turn));
            // messageStart is the user message; replay everything the agent did after it.
            for (int m = Math.max(0, turn.messageStart + 1); m < Math.min(end, rec.messages.size()); m++) {
                exchange.view.replay(rec.messages.get(m));
            }
            exchange.view.finish(turn.toResult());
        }
        if (exchanges.isEmpty()) {
            messagesBox.getChildren().setAll(emptyLabel);
        }
        selectCurrentInHistory();
        inputArea.requestFocus();
    }

    private static List<Attachment> attachmentsOf(ConversationRecord rec, TurnRecord turn) {
        if (turn.messageStart >= 0 && turn.messageStart < rec.messages.size()) {
            ChatMessage message = rec.messages.get(turn.messageStart);
            if (message.role() == Role.USER) {
                return message.attachments();
            }
        }
        return List.of();
    }

    private Exchange addExchange(TurnRecord turn, HarnessSettings settings, List<Attachment> attachments) {
        messagesBox.getChildren().remove(emptyLabel);
        Exchange exchange = new Exchange(turn, attachments);
        exchange.view = new AssistantTurnView(settings.reply, this::send, () -> regenerate(exchange));
        exchanges.add(exchange);
        messagesBox.getChildren().addAll(exchange.userNode, exchange.view.root());
        return exchange;
    }

    @FXML
    private void onSend() {
        String text = inputArea.getText();
        if (isRunning() || loadingAttachments.get() > 0 || ((text == null || text.isBlank()) && pending.isEmpty())) {
            return;
        }
        List<Attachment> attachments = List.copyOf(pending);
        pending.clear();
        inputArea.clear();
        start(text == null ? "" : text.strip(), attachments);
    }

    /** Used by buttons in generated UI to send an action back to the agent (composer attachments stay pending). */
    void send(String text) {
        if (text == null || text.isBlank() || isRunning()) {
            return;
        }
        start(text.strip(), List.of());
    }

    /** Rewinds the chat to just before {@code exchange} and runs the same request again. */
    private void regenerate(Exchange exchange) {
        int index = exchanges.indexOf(exchange);
        if (isRunning() || index < 0) {
            return;
        }
        int nodeIndex = messagesBox.getChildren().indexOf(exchange.userNode);
        if (nodeIndex >= 0) {
            messagesBox.getChildren().remove(nodeIndex, messagesBox.getChildren().size());
        }
        exchanges.subList(index, exchanges.size()).clear();
        record.turns.subList(Math.min(index, record.turns.size()), record.turns.size()).clear();
        conversation.truncate(Math.min(exchange.turn.messageStart, conversation.size()));
        LOG.info("Regenerating turn {} of conversation {}", index + 1, record.id);
        start(exchange.turn.userText, exchange.attachments);
    }

    private void start(String text, List<Attachment> attachments) {
        onModelChosen();
        HarnessSettings settings = store.current();
        ConversationRecord runRecord = record;
        Conversation runConversation = conversation;
        TurnRecord turn = new TurnRecord(text, runConversation.size());
        runRecord.turns.add(turn);
        Exchange exchange = addExchange(turn, settings, attachments);
        setRunning(true);
        AtomicBoolean cancel = new AtomicBoolean();
        cancelFlag = cancel;
        running = executor.submit(() -> {
            try {
                runAgent(text, attachments, exchange.view, runConversation, cancel, result -> {
                    turn.complete(result);
                    persist(runRecord, runConversation);
                    if (cancelFlag == cancel) {
                        setRunning(false);
                    }
                });
            } catch (Throwable t) {
                LOG.error("Agent run crashed", t);
                Platform.runLater(() -> {
                    exchange.view.notice("Unexpected error: " + t.getMessage());
                    turn.complete(new AgentRunResult(AgentRunResult.Status.ERROR, null, t.getMessage(), 0, 0));
                    persist(runRecord, runConversation);
                    if (cancelFlag == cancel) {
                        setRunning(false);
                    }
                });
            }
        });
    }

    private void persist(ConversationRecord rec, Conversation transcript) {
        rec.messages = new ArrayList<>(transcript.snapshot());
        rec.model = store.current().model.model;
        if (rec != record) {
            // A run finishing after the user switched chats: honour any rename made since.
            history.load(rec.id).ifPresent(saved -> rec.title = saved.title);
        }
        try {
            history.save(rec);
        } catch (RuntimeException e) {
            LOG.error("Could not save conversation {}", rec.id, e);
        }
        refreshHistory();
    }

    private void runAgent(String text, List<Attachment> attachments, AssistantTurnView turn, Conversation transcript, AtomicBoolean cancel,
            java.util.function.Consumer<AgentRunResult> onDone) {
        agentLoop.run(text, attachments, transcript, new AgentListener() {
            @Override
            public void onToken(String token) {
                Platform.runLater(() -> turn.appendToken(token));
            }

            @Override
            public void onThinking(String thinking) {
                Platform.runLater(() -> turn.showThinking(thinking));
            }

            @Override
            public void onIntermediateReply(String reply) {
                Platform.runLater(turn::resetStream);
            }

            @Override
            public void onToolCall(ToolCall call) {
                Platform.runLater(() -> turn.toolCall(call));
            }

            @Override
            public void onToolResult(ToolCall call, ToolResult result) {
                Platform.runLater(() -> turn.toolResult(call, result));
            }

            @Override
            public void onNotice(String notice) {
                Platform.runLater(() -> turn.notice(notice));
            }

            @Override
            public void onFinished(AgentRunResult result) {
                Platform.runLater(() -> {
                    turn.finish(result);
                    onDone.accept(result);
                });
            }
        }, cancel);
    }

    // ---- attachments -------------------------------------------------------------------------------------------

    private void initAttachments() {
        sendButton.disableProperty().bind(Bindings.createBooleanBinding(
                () -> !stopButton.isDisable() || loadingAttachments.get() > 0, stopButton.disableProperty(),
                loadingAttachments));
        pending.addListener((javafx.collections.ListChangeListener<Attachment>) c -> renderPending());
        root.setOnDragOver(e -> {
            if (e.getDragboard().hasFiles() && e.getDragboard().getFiles().stream()
                    .anyMatch(f -> AttachmentLoader.supported(f.toPath()))) {
                e.acceptTransferModes(TransferMode.COPY);
                if (!composer.getStyleClass().contains("drop-target")) {
                    composer.getStyleClass().add("drop-target");
                }
            }
            e.consume();
        });
        root.setOnDragExited(e -> composer.getStyleClass().remove("drop-target"));
        root.setOnDragDropped(e -> {
            boolean ok = e.getDragboard().hasFiles();
            if (ok) {
                attach(e.getDragboard().getFiles());
            }
            e.setDropCompleted(ok);
            e.consume();
        });
        // Pasting copied files (e.g. from the file explorer) attaches them.
        inputArea.addEventFilter(KeyEvent.KEY_PRESSED, e -> {
            if (e.isShortcutDown() && e.getCode() == KeyCode.V && Clipboard.getSystemClipboard().hasFiles()) {
                attach(Clipboard.getSystemClipboard().getFiles());
                e.consume();
            }
        });
    }

    @FXML
    private void onAttach() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Attach files");
        chooser.getExtensionFilters().addAll(
                filter("Supported files", AttachmentLoader.TEXT_EXTENSIONS, AttachmentLoader.PDF_EXTENSIONS,
                        AttachmentLoader.IMAGE_EXTENSIONS, AttachmentLoader.AUDIO_EXTENSIONS),
                filter("Markdown / text", AttachmentLoader.TEXT_EXTENSIONS),
                filter("PDF", AttachmentLoader.PDF_EXTENSIONS),
                filter("Pictures", AttachmentLoader.IMAGE_EXTENSIONS),
                filter("Audio", AttachmentLoader.AUDIO_EXTENSIONS));
        List<File> files = chooser.showOpenMultipleDialog(root.getScene().getWindow());
        if (files != null) {
            attach(files);
        }
    }

    private static FileChooser.ExtensionFilter filter(String title, String[]... groups) {
        List<String> patterns = new ArrayList<>();
        for (String[] group : groups) {
            for (String ext : group) {
                patterns.add("*." + ext);
            }
        }
        return new FileChooser.ExtensionFilter(title, patterns);
    }

    /** Loads files off the UI thread (PDF parsing can take a moment) and adds them to the pending list. */
    void attach(List<File> files) {
        ContextSettings limits = store.current().context;
        List<String> problems = new ArrayList<>();
        for (File file : files) {
            if (pending.size() + loadingAttachments.get() >= limits.maxAttachments) {
                problems.add(file.getName() + ": at most " + limits.maxAttachments + " files per message");
                continue;
            }
            if (!AttachmentLoader.supported(file.toPath())) {
                problems.add(file.getName() + ": unsupported type (use markdown/text, PDF, pictures or audio)");
                continue;
            }
            loadingAttachments.set(loadingAttachments.get() + 1);
            CompletableFuture.supplyAsync(() -> {
                try {
                    return AttachmentLoader.load(file.toPath(), limits);
                } catch (IOException e) {
                    throw new java.io.UncheckedIOException(e);
                }
            }).whenComplete((attachment, error) -> Platform.runLater(() -> {
                loadingAttachments.set(loadingAttachments.get() - 1);
                if (error == null) {
                    pending.add(attachment);
                    return;
                }
                Throwable cause = error instanceof java.util.concurrent.CompletionException && error.getCause() != null
                        ? error.getCause() : error;
                LOG.warn("Could not attach {}", file, cause);
                showAttachProblems(List.of(cause.getMessage() == null ? file.getName() : cause.getMessage()));
            }));
        }
        if (!problems.isEmpty()) {
            problems.forEach(p -> LOG.warn("Attachment rejected: {}", p));
            showAttachProblems(problems);
        }
    }

    private void showAttachProblems(List<String> problems) {
        Alert alert = new Alert(Alert.AlertType.WARNING, String.join("\n", problems), ButtonType.OK);
        alert.initOwner(root.getScene().getWindow());
        alert.setHeaderText("Some files were not attached");
        alert.show();
    }

    private void renderPending() {
        attachmentsBar.getChildren().setAll(pending.stream()
                .map(a -> (Node) AttachmentViews.chip(a, () -> pending.remove(a))).toList());
        boolean any = !pending.isEmpty();
        attachmentsBar.setVisible(any);
        attachmentsBar.setManaged(any);
    }

    @FXML
    private void onStop() {
        if (cancelFlag != null) {
            cancelFlag.set(true);
        }
        if (running != null) {
            running.cancel(true);
        }
    }

    private boolean isRunning() {
        return running != null && !running.isDone();
    }

    private void setRunning(boolean value) {
        stopButton.setDisable(!value);
        if (!value) {
            inputArea.requestFocus();
        }
    }

    public void shutdown() {
        onStop();
        executor.shutdownNow();
    }
}
