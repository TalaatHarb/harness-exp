package net.talaatharb.harness.ui;

import java.util.List;

import com.google.inject.Inject;

import javafx.fxml.FXML;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;
import javafx.stage.Window;
import net.talaatharb.harness.config.HarnessSettings;
import net.talaatharb.harness.config.SettingsStore;
import net.talaatharb.harness.ui.sections.AgentLoopSectionController;
import net.talaatharb.harness.ui.sections.ApprovalSectionController;
import net.talaatharb.harness.ui.sections.ContextSectionController;
import net.talaatharb.harness.ui.sections.GuardrailsSectionController;
import net.talaatharb.harness.ui.sections.ModelSectionController;
import net.talaatharb.harness.ui.sections.ReplySectionController;
import net.talaatharb.harness.ui.sections.SettingsSection;
import net.talaatharb.harness.ui.sections.SkillsSectionController;
import net.talaatharb.harness.ui.sections.SystemPromptSectionController;
import net.talaatharb.harness.ui.sections.ToolsSectionController;

/** Hosts the configuration sections; edits a draft and commits it to the {@link SettingsStore} on save. */
public class SettingsController {
    private final SettingsStore store;
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

    @Inject
    public SettingsController(SettingsStore store) {
        this.store = store;
    }

    @FXML
    private void initialize() {
        sections = List.of(modelSectionController, systemPromptSectionController, skillsSectionController,
                contextSectionController, toolsSectionController, agentLoopSectionController,
                guardrailsSectionController, approvalSectionController, replySectionController);
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
}
