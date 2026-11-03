package net.talaatharb.harness.ui.sections;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.ObjectMapper;

import com.google.inject.Inject;

import javafx.application.Platform;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.fxml.FXML;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.Spinner;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.cell.CheckBoxTableCell;
import javafx.stage.FileChooser;
import javafx.stage.WindowEvent;
import net.talaatharb.harness.config.HarnessSettings;
import net.talaatharb.harness.tools.Tool;
import net.talaatharb.harness.tools.ToolRegistry;
import net.talaatharb.harness.tools.dynamic.DynamicToolRegistry;
import net.talaatharb.harness.tools.dynamic.DynamicToolSpec;
import net.talaatharb.harness.tools.mcp.McpClient;
import net.talaatharb.harness.tools.mcp.McpServerConfig;
import net.talaatharb.harness.tools.mcp.McpToolSource;

public class ToolsSectionController implements SettingsSection {
    private static final Logger LOG = LoggerFactory.getLogger(ToolsSectionController.class);
    private final ToolRegistry registry;
    private final McpToolSource mcp;
    private final DynamicToolRegistry dynamic;
    private final ObservableList<ToolRow> tools = FXCollections.observableArrayList();
    private final ObservableList<McpRow> servers = FXCollections.observableArrayList();

    @FXML private CheckBox toolsEnabledCheck;
    @FXML private Spinner<Integer> maxOutputSpinner;
    @FXML private TableView<ToolRow> toolTable;
    @FXML private TableColumn<ToolRow, Boolean> toolEnabledCol;
    @FXML private TableColumn<ToolRow, String> toolNameCol;
    @FXML private TableColumn<ToolRow, String> toolRiskCol;
    @FXML private TableColumn<ToolRow, String> toolSourceCol;
    @FXML private TableColumn<ToolRow, String> toolDescCol;
    @FXML private TableView<McpRow> mcpTable;
    @FXML private TableColumn<McpRow, Boolean> mcpEnabledCol;
    @FXML private TableColumn<McpRow, String> mcpNameCol;
    @FXML private TableColumn<McpRow, String> mcpTransportCol;
    @FXML private TableColumn<McpRow, String> mcpEndpointCol;
    @FXML private TableColumn<McpRow, String> mcpEnvCol;
    @FXML private TableColumn<McpRow, String> mcpStatusCol;
    @FXML private TextField mcpEnvField;
    @FXML private Button mcpAddButton;
    @FXML private Button mcpTestButton;
    @FXML private Spinner<Integer> mcpConnectTimeoutSpinner;
    @FXML private Spinner<Integer> mcpToolTimeoutSpinner;
    @FXML private Label mcpMessageLabel;
    private HarnessSettings loaded;
    private final Runnable mcpListener = () -> Platform.runLater(this::onMcpChanged);
    @FXML private TextField mcpNameField;
    @FXML private ComboBox<McpServerConfig.Transport> mcpTransportCombo;
    @FXML private TextField mcpEndpointField;
    @FXML private Label dynamicStatusLabel;

    static final class ToolRow {
        final Tool tool;
        final SimpleBooleanProperty enabled = new SimpleBooleanProperty();

        ToolRow(Tool tool, boolean enabled) {
            this.tool = tool;
            this.enabled.set(enabled);
        }
    }

    static final class McpRow {
        final McpServerConfig config;
        final SimpleBooleanProperty enabled = new SimpleBooleanProperty();

        McpRow(McpServerConfig config) {
            this.config = config;
            this.enabled.set(config.enabled);
        }
    }

    @Inject
    public ToolsSectionController(ToolRegistry registry, McpToolSource mcp, DynamicToolRegistry dynamic) {
        this.registry = registry;
        this.mcp = mcp;
        this.dynamic = dynamic;
    }

    @FXML
    private void initialize() {
        toolTable.setItems(tools);
        toolEnabledCol.setCellValueFactory(c -> c.getValue().enabled);
        toolEnabledCol.setCellFactory(CheckBoxTableCell.forTableColumn(toolEnabledCol));
        toolEnabledCol.setEditable(true);
        toolNameCol.setCellValueFactory(c -> new ReadOnlyStringWrapper(c.getValue().tool.name()));
        toolRiskCol.setCellValueFactory(c -> new ReadOnlyStringWrapper(c.getValue().tool.risk().name()));
        toolSourceCol.setCellValueFactory(c -> new ReadOnlyStringWrapper(c.getValue().tool.source()));
        toolDescCol.setCellValueFactory(c -> new ReadOnlyStringWrapper(c.getValue().tool.description()));

        mcpTable.setItems(servers);
        mcpEnabledCol.setCellValueFactory(c -> c.getValue().enabled);
        mcpEnabledCol.setCellFactory(CheckBoxTableCell.forTableColumn(mcpEnabledCol));
        mcpEnabledCol.setEditable(true);
        mcpNameCol.setCellValueFactory(c -> new ReadOnlyStringWrapper(c.getValue().config.name));
        mcpTransportCol.setCellValueFactory(c -> new ReadOnlyStringWrapper(c.getValue().config.transport.name()));
        mcpEndpointCol.setCellValueFactory(c -> new ReadOnlyStringWrapper(c.getValue().config.endpoint()));
        mcpEnvCol.setCellValueFactory(c -> new ReadOnlyStringWrapper(String.join(", ", c.getValue().config.env.keySet())));
        mcpStatusCol.setCellValueFactory(c -> new ReadOnlyStringWrapper(mcp.status(c.getValue().config)));
        mcpTable.getSelectionModel().selectedItemProperty().addListener((o, old, row) -> {
            if (row != null) {
                McpServerConfig c = row.config;
                mcpNameField.setText(c.name);
                mcpTransportCombo.setValue(c.transport);
                mcpEndpointField.setText(c.endpoint());
                mcpEnvField.setText(McpServerConfig.formatEnv(c.env));
            }
        });
        mcpNameField.textProperty().addListener((o, a, name) -> mcpAddButton.setText(
                servers.stream().anyMatch(r -> r.config.name.equals(name.strip())) ? "Update server" : "Add server"));
        // Live status while the window is open; stop listening once it closes.
        mcp.addListener(mcpListener);
        mcpTable.sceneProperty().addListener((o, oldScene, scene) -> {
            if (scene != null) {
                scene.windowProperty().addListener((ow, oldWindow, window) -> {
                    if (window != null) {
                        window.addEventHandler(WindowEvent.WINDOW_HIDDEN, e -> mcp.removeListener(mcpListener));
                    }
                });
            }
        });

        mcpTransportCombo.setItems(FXCollections.observableArrayList(McpServerConfig.Transport.values()));
        mcpTransportCombo.setValue(McpServerConfig.Transport.STDIO);

        dynamicStatusLabel.setText((dynamic.compilerAvailable() ? "Java compiler available"
                : "Java compiler NOT available (run on a JDK)") + " · stored in " + dynamic.storageDir());
    }

    @FXML
    private void onViewDynamicSource() {
        selectedDynamicSpec().ifPresent(spec -> {
            TextArea source = new TextArea(spec.sourceCode);
            source.setEditable(false);
            source.getStyleClass().add("mono");
            source.setPrefSize(720, 460);
            Alert alert = new Alert(Alert.AlertType.INFORMATION);
            alert.initOwner(toolTable.getScene().getWindow());
            alert.setTitle("Dynamic tool: " + spec.name);
            alert.setHeaderText(spec.description + "\nArguments: " + spec.argumentsHint
                    + (spec.createdAt == null ? "" : "\nCreated: " + spec.createdAt));
            alert.getDialogPane().setContent(source);
            alert.setResizable(true);
            alert.showAndWait();
        });
    }

    @FXML
    private void onDeleteDynamicTool() {
        ToolRow row = toolTable.getSelectionModel().getSelectedItem();
        if (selectedDynamicSpec().isEmpty()) {
            return;
        }
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
                "Delete the agent-created tool '" + row.tool.name() + "'? This takes effect immediately.",
                ButtonType.OK, ButtonType.CANCEL);
        confirm.initOwner(toolTable.getScene().getWindow());
        if (confirm.showAndWait().orElse(ButtonType.CANCEL) == ButtonType.OK && dynamic.remove(row.tool.name())) {
            tools.remove(row);
        }
    }

    private Optional<DynamicToolSpec> selectedDynamicSpec() {
        ToolRow row = toolTable.getSelectionModel().getSelectedItem();
        if (row == null) {
            dynamicStatusLabel.setText("Select an agent-created tool (source: dynamic) in the table first.");
            return Optional.empty();
        }
        Optional<DynamicToolSpec> spec = dynamic.spec(row.tool.name());
        if (spec.isEmpty()) {
            dynamicStatusLabel.setText("'" + row.tool.name() + "' is not an agent-created tool.");
        }
        return spec;
    }

    @Override
    public void load(HarnessSettings s) {
        loaded = s;
        toolsEnabledCheck.setSelected(s.tools.toolsEnabled);
        SettingsSection.intSpinner(maxOutputSpinner, 100, 200_000, s.tools.maxToolOutputChars);
        SettingsSection.intSpinner(mcpConnectTimeoutSpinner, 5, 600, s.tools.mcpConnectTimeoutSeconds);
        SettingsSection.intSpinner(mcpToolTimeoutSpinner, 1, 3_600, s.tools.mcpToolTimeoutSeconds);
        tools.setAll(registry.all(s).stream()
                .map(t -> new ToolRow(t, !s.tools.disabledTools.contains(t.name()))).toList());
        servers.setAll(s.tools.mcpServers.stream().map(c -> new McpRow(c.copy())).toList());
    }

    /** A server connected, failed or changed its tools: refresh statuses and the tool list (keeping checkboxes). */
    private void onMcpChanged() {
        mcpTable.refresh();
        if (loaded == null) {
            return;
        }
        Map<String, Boolean> enabled = new HashMap<>();
        tools.forEach(r -> enabled.put(r.tool.name(), r.enabled.get()));
        tools.setAll(registry.all(loaded).stream().map(t -> new ToolRow(t,
                enabled.getOrDefault(t.name(), !loaded.tools.disabledTools.contains(t.name())))).toList());
    }

    @Override
    public void apply(HarnessSettings s) {
        s.tools.toolsEnabled = toolsEnabledCheck.isSelected();
        s.tools.maxToolOutputChars = SettingsSection.spinnerValue(maxOutputSpinner);
        s.tools.mcpConnectTimeoutSeconds = SettingsSection.spinnerValue(mcpConnectTimeoutSpinner);
        s.tools.mcpToolTimeoutSeconds = SettingsSection.spinnerValue(mcpToolTimeoutSpinner);
        s.tools.disabledTools.clear();
        tools.stream().filter(r -> !r.enabled.get()).forEach(r -> s.tools.disabledTools.add(r.tool.name()));
        List<McpServerConfig> configs = new ArrayList<>();
        for (McpRow row : servers) {
            McpServerConfig c = row.config;
            configs.add(new McpServerConfig(c.name, c.transport, c.command, c.url, row.enabled.get(), c.env));
        }
        s.tools.mcpServers = configs;
    }

    /** The server described by the input fields, or {@code null} (with a message) when incomplete. */
    private McpServerConfig fromFields() {
        String name = mcpNameField.getText().strip();
        String endpoint = mcpEndpointField.getText().strip();
        if (name.isEmpty() || endpoint.isEmpty()) {
            mcpMessageLabel.setText("Enter a name and a command (stdio) or URL (http) first.");
            return null;
        }
        McpServerConfig.Transport transport = mcpTransportCombo.getValue();
        boolean http = transport == McpServerConfig.Transport.HTTP;
        return new McpServerConfig(name, transport, http ? "" : endpoint, http ? endpoint : "", true,
                McpServerConfig.parseEnv(mcpEnvField.getText()));
    }

    @FXML
    private void onAddServer() {
        McpServerConfig config = fromFields();
        if (config == null) {
            return;
        }
        for (int i = 0; i < servers.size(); i++) {
            if (servers.get(i).config.name.equals(config.name)) {
                config.enabled = servers.get(i).enabled.get();
                servers.set(i, new McpRow(config));
                mcpMessageLabel.setText("Updated '" + config.name + "'. Apply to reconnect.");
                clearFields();
                return;
            }
        }
        servers.add(new McpRow(config));
        mcpMessageLabel.setText("Added '" + config.name + "'. Apply to connect.");
        clearFields();
    }

    private void clearFields() {
        mcpTable.getSelectionModel().clearSelection();
        mcpNameField.clear();
        mcpEndpointField.clear();
        mcpEnvField.clear();
    }

    @FXML
    private void onTestServer() {
        McpServerConfig config = fromFields();
        if (config == null) {
            McpRow row = mcpTable.getSelectionModel().getSelectedItem();
            if (row == null) {
                return;
            }
            config = row.config.copy();
        }
        McpServerConfig target = config;
        mcpTestButton.setDisable(true);
        mcpMessageLabel.setText("Testing '" + target.name + "'… (npx/uvx may download the server first)");
        CompletableFuture.supplyAsync(() -> {
            try {
                return mcp.test(target);
            } catch (Exception e) {
                throw new CompletionException(e);
            }
        }).whenComplete((list, error) -> Platform.runLater(() -> {
            mcpTestButton.setDisable(false);
            if (error != null) {
                Throwable cause = error instanceof CompletionException && error.getCause() != null
                        ? error.getCause() : error;
                LOG.warn("MCP test of '{}' failed", target.name, cause);
                mcpMessageLabel.setText("✗ " + target.name + ": " + cause.getMessage());
                return;
            }
            mcpMessageLabel.setText("✓ " + target.name + ": " + list.size() + " tool(s): "
                    + list.stream().map(McpClient.ToolInfo::name).collect(Collectors.joining(", ")));
        }));
    }

    @FXML
    private void onReconnectServer() {
        McpRow row = mcpTable.getSelectionModel().getSelectedItem();
        if (row == null) {
            mcpMessageLabel.setText("Select a server first.");
            return;
        }
        mcp.reconnect(row.config.name);
        mcpMessageLabel.setText("Reconnecting '" + row.config.name + "'…");
    }

    @FXML
    private void onImportMcpJson() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Import MCP servers");
        chooser.getExtensionFilters().addAll(new FileChooser.ExtensionFilter("JSON", "*.json"),
                new FileChooser.ExtensionFilter("All files", "*.*"));
        File file = chooser.showOpenDialog(mcpTable.getScene().getWindow());
        if (file == null) {
            return;
        }
        try {
            List<McpServerConfig> found = McpServerConfig.fromMcpJson(new ObjectMapper().readTree(file));
            int added = 0;
            int updated = 0;
            for (McpServerConfig config : found) {
                McpRow existing = servers.stream().filter(r -> r.config.name.equals(config.name)).findFirst()
                        .orElse(null);
                if (existing != null) {
                    servers.set(servers.indexOf(existing), new McpRow(config));
                    updated++;
                } else {
                    servers.add(new McpRow(config));
                    added++;
                }
            }
            mcpMessageLabel.setText(found.isEmpty() ? "No servers found in " + file.getName()
                    : "Imported " + added + " new and " + updated + " updated server(s) from " + file.getName()
                            + ". Apply to connect.");
        } catch (IOException | RuntimeException e) {
            LOG.error("Could not import MCP servers from {}", file, e);
            mcpMessageLabel.setText("Could not read " + file.getName() + ": " + e.getMessage());
        }
    }

    @FXML
    private void onRemoveServer() {
        McpRow selected = mcpTable.getSelectionModel().getSelectedItem();
        if (selected != null) {
            servers.remove(selected);
        }
    }
}
