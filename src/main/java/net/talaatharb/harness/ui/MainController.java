package net.talaatharb.harness.ui;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.inject.Inject;

import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextArea;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.VBox;
import net.talaatharb.harness.agent.AgentListener;
import net.talaatharb.harness.agent.AgentLoop;
import net.talaatharb.harness.agent.AgentRunResult;
import net.talaatharb.harness.agent.Conversation;
import net.talaatharb.harness.config.HarnessSettings;
import net.talaatharb.harness.config.SettingsStore;
import net.talaatharb.harness.llm.ModelProviderRegistry;
import net.talaatharb.harness.tools.ToolCall;
import net.talaatharb.harness.tools.ToolResult;

/** ChatGPT-like main window: a model picker, the conversation and a composer. Everything else lives in settings. */
public class MainController {
    private static final Logger LOG = LoggerFactory.getLogger(MainController.class);
    private final AgentLoop agentLoop;
    private final SettingsStore store;
    private final ModelProviderRegistry providers;
    private final SettingsWindow settingsWindow;
    private final Conversation conversation = new Conversation();
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "agent-loop");
        t.setDaemon(true);
        return t;
    });

    private Future<?> running;
    private AtomicBoolean cancelFlag;

    @FXML private ComboBox<String> modelCombo;
    @FXML private Label statusLabel;
    @FXML private ScrollPane scrollPane;
    @FXML private VBox messagesBox;
    @FXML private Label emptyLabel;
    @FXML private TextArea inputArea;
    @FXML private Button sendButton;
    @FXML private Button stopButton;

    @Inject
    public MainController(AgentLoop agentLoop, SettingsStore store, ModelProviderRegistry providers,
            SettingsWindow settingsWindow) {
        this.agentLoop = agentLoop;
        this.store = store;
        this.providers = providers;
        this.settingsWindow = settingsWindow;
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
        onRefreshModels();
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
        conversation.clear();
        messagesBox.getChildren().setAll(emptyLabel);
    }

    @FXML
    private void onOpenSettings() {
        settingsWindow.show(messagesBox.getScene().getWindow());
    }

    @FXML
    private void onSend() {
        send(inputArea.getText());
    }

    /** Also used by buttons in generated UI to send an action back to the agent. */
    void send(String text) {
        if (text == null || text.isBlank() || isRunning()) {
            return;
        }
        onModelChosen();
        inputArea.clear();
        messagesBox.getChildren().remove(emptyLabel);
        messagesBox.getChildren().add(ChatBubbles.user(text.strip()));

        HarnessSettings settings = store.current();
        AssistantTurnView turn = new AssistantTurnView(settings.reply, this::send);
        messagesBox.getChildren().add(turn.root());
        setRunning(true);
        AtomicBoolean cancel = new AtomicBoolean();
        cancelFlag = cancel;
        running = executor.submit(() -> {
            try {
                runAgent(text.strip(), turn, cancel);
            } catch (Throwable t) {
                LOG.error("Agent run crashed", t);
                Platform.runLater(() -> {
                    turn.notice("Unexpected error: " + t.getMessage());
                    setRunning(false);
                });
            }
        });
    }

    private void runAgent(String text, AssistantTurnView turn, AtomicBoolean cancel) {
        agentLoop.run(text, conversation, new AgentListener() {
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
                    setRunning(false);
                });
            }
        }, cancel);
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
        sendButton.setDisable(value);
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
