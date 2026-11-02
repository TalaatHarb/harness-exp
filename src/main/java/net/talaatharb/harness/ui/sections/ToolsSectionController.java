package net.talaatharb.harness.ui.sections;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.google.inject.Inject;

import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.fxml.FXML;
import javafx.scene.control.Alert;
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
import net.talaatharb.harness.config.HarnessSettings;
import net.talaatharb.harness.tools.Tool;
import net.talaatharb.harness.tools.ToolRegistry;
import net.talaatharb.harness.tools.dynamic.DynamicToolRegistry;
import net.talaatharb.harness.tools.dynamic.DynamicToolSpec;
import net.talaatharb.harness.tools.mcp.McpServerConfig;
import net.talaatharb.harness.tools.mcp.McpToolSource;

public class ToolsSectionController implements SettingsSection {
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
    @FXML private TableColumn<McpRow, String> mcpStatusCol;
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
        mcpStatusCol.setCellValueFactory(c -> new ReadOnlyStringWrapper(mcp.status(c.getValue().config)));

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
        toolsEnabledCheck.setSelected(s.tools.toolsEnabled);
        SettingsSection.intSpinner(maxOutputSpinner, 100, 200_000, s.tools.maxToolOutputChars);
        tools.setAll(registry.all(s).stream()
                .map(t -> new ToolRow(t, !s.tools.disabledTools.contains(t.name()))).toList());
        servers.setAll(s.tools.mcpServers.stream().map(c -> new McpRow(
                new McpServerConfig(c.name, c.transport, c.command, c.url, c.enabled))).toList());
    }

    @Override
    public void apply(HarnessSettings s) {
        s.tools.toolsEnabled = toolsEnabledCheck.isSelected();
        s.tools.maxToolOutputChars = SettingsSection.spinnerValue(maxOutputSpinner);
        s.tools.disabledTools.clear();
        tools.stream().filter(r -> !r.enabled.get()).forEach(r -> s.tools.disabledTools.add(r.tool.name()));
        List<McpServerConfig> configs = new ArrayList<>();
        for (McpRow row : servers) {
            McpServerConfig c = row.config;
            configs.add(new McpServerConfig(c.name, c.transport, c.command, c.url, row.enabled.get()));
        }
        s.tools.mcpServers = configs;
    }

    @FXML
    private void onAddServer() {
        String name = mcpNameField.getText().trim();
        String endpoint = mcpEndpointField.getText().trim();
        if (name.isEmpty() || endpoint.isEmpty()) {
            return;
        }
        McpServerConfig.Transport transport = mcpTransportCombo.getValue();
        boolean http = transport == McpServerConfig.Transport.HTTP;
        servers.add(new McpRow(new McpServerConfig(name, transport, http ? "" : endpoint, http ? endpoint : "",
                true)));
        mcpNameField.clear();
        mcpEndpointField.clear();
    }

    @FXML
    private void onRemoveServer() {
        McpRow selected = mcpTable.getSelectionModel().getSelectedItem();
        if (selected != null) {
            servers.remove(selected);
        }
    }
}
