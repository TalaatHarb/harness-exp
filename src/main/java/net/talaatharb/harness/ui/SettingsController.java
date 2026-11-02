package net.talaatharb.harness.ui;

import java.io.File;
import java.io.IOException;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.inject.Inject;

import javafx.fxml.FXML;
import javafx.geometry.Insets;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Dialog;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.stage.Window;
import net.talaatharb.harness.config.HarnessSettings;
import net.talaatharb.harness.config.SettingsBundle;
import net.talaatharb.harness.config.SettingsStore;
import net.talaatharb.harness.config.SettingsTransfer;
import net.talaatharb.harness.ui.sections.AgentLoopSectionController;
import net.talaatharb.harness.ui.sections.ApprovalSectionController;
import net.talaatharb.harness.ui.sections.ContextSectionController;
import net.talaatharb.harness.ui.sections.GuardrailsSectionController;
import net.talaatharb.harness.ui.sections.ModelSectionController;
import net.talaatharb.harness.ui.sections.ReplySectionController;
import net.talaatharb.harness.ui.sections.SettingsSection;
import net.talaatharb.harness.ui.sections.SkillsSectionController;
import net.talaatharb.harness.ui.sections.SpeechSectionController;
import net.talaatharb.harness.ui.sections.StorageSectionController;
import net.talaatharb.harness.ui.sections.SystemPromptSectionController;
import net.talaatharb.harness.ui.sections.ToolsSectionController;

/** Hosts the configuration sections; edits a draft and commits it to the {@link SettingsStore} on save. */
public class SettingsController {
    private static final Logger LOG = LoggerFactory.getLogger(SettingsController.class);
    private final SettingsStore store;
    private final SettingsTransfer transfer;
    private HarnessSettings draft;
    private List<SettingsSection> sections;

    @FXML private ModelSectionController modelSectionController;
    @FXML private SystemPromptSectionController systemPromptSectionController;
    @FXML private SkillsSectionController skillsSectionController;
    @FXML private ContextSectionController contextSectionController;
    @FXML private ToolsSectionController toolsSectionController;
    @FXML private AgentLoopSectionController agentLoopSectionController;
    @FXML private GuardrailsSectionController guardrailsSectionController;
    @FXML private ApprovalSectionController approvalSectionController;
    @FXML private ReplySectionController replySectionController;
    @FXML private SpeechSectionController speechSectionController;
    @FXML private StorageSectionController storageSectionController;

    @Inject
    public SettingsController(SettingsStore store, SettingsTransfer transfer) {
        this.store = store;
        this.transfer = transfer;
    }

    @FXML
    private void initialize() {
        sections = List.of(modelSectionController, systemPromptSectionController, skillsSectionController,
                contextSectionController, toolsSectionController, agentLoopSectionController,
                guardrailsSectionController, approvalSectionController, replySectionController,
                speechSectionController, storageSectionController);
        sections.forEach(s -> s.setDraftSource(this::collect));
        load(store.copy());
    }

    private void load(HarnessSettings settings) {
        draft = settings;
        sections.forEach(s -> s.load(draft));
    }

    private HarnessSettings collect() {
        HarnessSettings copy = store.mapper().convertValue(draft, HarnessSettings.class);
        sections.forEach(s -> s.apply(copy));
        return copy;
    }

    @FXML
    private void onApply() {
        draft = collect();
        store.replace(draft);
    }

    @FXML
    private void onSave() {
        onApply();
        window().hide();
    }

    @FXML
    private void onCancel() {
        window().hide();
    }

    @FXML
    private void onResetDefaults() {
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
                "Reset every section to its default? Changes are not saved until you click Save.",
                ButtonType.OK, ButtonType.CANCEL);
        confirm.initOwner(window());
        confirm.setHeaderText(null);
        if (confirm.showAndWait().orElse(ButtonType.CANCEL) == ButtonType.OK) {
            load(new HarnessSettings());
        }
    }

    private Window window() {
        return modelSectionController.root().getScene().getWindow();
    }

    @FXML
    private void onExport() {
        CheckBox skills = new CheckBox("Skills (skills folder contents)");
        CheckBox tools = new CheckBox("Dynamic tools (source code)");
        CheckBox apiKey = new CheckBox("API key (it's a secret: only share the file with care)");
        skills.setSelected(true);
        tools.setSelected(true);
        Dialog<ButtonType> dialog = optionsDialog("Export harness settings",
                "Exports the settings as currently shown in this window (including unsaved edits).",
                skills, tools, apiKey);
        if (dialog.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) {
            return;
        }
        FileChooser chooser = jsonChooser("Export harness settings");
        chooser.setInitialFileName("harness-settings.json");
        File file = chooser.showSaveDialog(window());
        if (file == null) {
            return;
        }
        try {
            SettingsBundle bundle = transfer.export(collect(), new SettingsTransfer.ExportOptions(
                    skills.isSelected(), tools.isSelected(), apiKey.isSelected()));
            transfer.write(bundle, file.toPath());
            info("Exported", "Saved to " + file + "\n" + bundle.skills.size() + " skill(s), "
                    + bundle.dynamicTools.size() + " dynamic tool(s).");
        } catch (IOException | RuntimeException e) {
            LOG.error("Export to {} failed", file, e);
            error("Export failed", e.getMessage());
        }
    }

    @FXML
    private void onImport() {
        File file = jsonChooser("Import harness settings").showOpenDialog(window());
        if (file == null) {
            return;
        }
        SettingsBundle bundle;
        try {
            bundle = transfer.read(file.toPath());
        } catch (IOException | RuntimeException e) {
            LOG.error("Could not read {}", file, e);
            error("Import failed", e.getMessage());
            return;
        }
        CheckBox settings = new CheckBox("Settings (model, system prompt, context, tools, loop, guardrails, …)");
        CheckBox keepKey = new CheckBox("Keep my current API key");
        CheckBox keepStorage = new CheckBox("Keep my current storage folders");
        CheckBox skills = new CheckBox("Skills (" + bundle.skills.size() + "; same-name skills are replaced)");
        CheckBox tools = new CheckBox("Dynamic tools (" + bundle.dynamicTools.size()
                + ") ⚠ compiles and runs Java code from the file");
        SettingsTransfer.ImportOptions defaults = SettingsTransfer.ImportOptions.defaults();
        settings.setSelected(defaults.settings());
        keepKey.setSelected(defaults.keepApiKey());
        keepStorage.setSelected(defaults.keepStorage());
        skills.setSelected(defaults.skills() && !bundle.skills.isEmpty());
        skills.setDisable(bundle.skills.isEmpty());
        tools.setSelected(defaults.dynamicTools());
        tools.setDisable(bundle.dynamicTools.isEmpty());
        keepKey.disableProperty().bind(settings.selectedProperty().not());
        keepStorage.disableProperty().bind(settings.selectedProperty().not());
        Dialog<ButtonType> dialog = optionsDialog("Import harness settings",
                "Import from " + file.getName() + (bundle.exportedAt == null ? "" : " (exported " + bundle.exportedAt
                        + ")") + ".\nThis applies immediately and replaces the current values.",
                settings, keepKey, keepStorage, skills, tools);
        if (dialog.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) {
            return;
        }
        SettingsTransfer.ImportReport report = transfer.apply(bundle, new SettingsTransfer.ImportOptions(
                settings.isSelected(), keepKey.isSelected(), keepStorage.isSelected(), skills.isSelected(),
                tools.isSelected()));
        load(store.copy());
        if (report.errors().isEmpty()) {
            info("Imported", report.summary());
        } else {
            error("Imported with problems", report.summary());
        }
    }

    private Dialog<ButtonType> optionsDialog(String title, String text, CheckBox... options) {
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.initOwner(window());
        dialog.setTitle(title);
        dialog.setHeaderText(text);
        VBox box = new VBox(8, options);
        box.setPadding(new Insets(8));
        dialog.getDialogPane().setContent(box);
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        return dialog;
    }

    private static FileChooser jsonChooser(String title) {
        FileChooser chooser = new FileChooser();
        chooser.setTitle(title);
        chooser.getExtensionFilters().addAll(new FileChooser.ExtensionFilter("JSON", "*.json"),
                new FileChooser.ExtensionFilter("All files", "*.*"));
        return chooser;
    }

    private void info(String header, String text) {
        Alert alert = new Alert(Alert.AlertType.INFORMATION, text, ButtonType.OK);
        alert.initOwner(window());
        alert.setHeaderText(header);
        alert.showAndWait();
    }

    private void error(String header, String text) {
        Alert alert = new Alert(Alert.AlertType.ERROR, text, ButtonType.OK);
        alert.initOwner(window());
        alert.setHeaderText(header);
        alert.showAndWait();
    }
}
