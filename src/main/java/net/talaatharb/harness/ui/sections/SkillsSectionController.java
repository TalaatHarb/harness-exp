package net.talaatharb.harness.ui.sections;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.inject.Inject;

import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.fxml.FXML;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar.ButtonData;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.VBox;
import net.talaatharb.harness.config.HarnessSettings;
import net.talaatharb.harness.skills.SkillDefinition;
import net.talaatharb.harness.skills.SkillRepository;

/**
 * Edits the skills folder directly (skills are files, not settings): Save writes the selected skill's
 * {@code SKILL.md}, Refresh re-reads the folder to pick up outside edits and agent-created skills.
 */
public class SkillsSectionController implements SettingsSection {
    private static final Logger LOG = LoggerFactory.getLogger(SkillsSectionController.class);

    private final SkillRepository repository;
    private final ObservableList<SkillDefinition> skills = FXCollections.observableArrayList();
    private final BooleanProperty dirty = new SimpleBooleanProperty();
    private SkillDefinition editing;
    private boolean updating;

    @FXML private VBox root;
    @FXML private TextField folderField;
    @FXML private Label statusLabel;
    @FXML private ListView<SkillDefinition> skillList;
    @FXML private VBox form;
    @FXML private CheckBox enabledCheck;
    @FXML private TextField nameField;
    @FXML private TextField descriptionField;
    @FXML private TextField triggersField;
    @FXML private TextArea instructionsArea;
    @FXML private Label fileLabel;
    @FXML private Button saveButton;
    @FXML private Button revertButton;
    @FXML private Button deleteButton;

    @Inject
    public SkillsSectionController(SkillRepository repository) {
        this.repository = repository;
    }

    @FXML
    private void initialize() {
        folderField.setText(repository.dir().toString());
        skillList.setItems(skills);
        skillList.setPlaceholder(new Label("No skills. Add one or drop a <name>/SKILL.md into the folder."));
        skillList.setCellFactory(list -> new ListCell<>() {
            @Override
            protected void updateItem(SkillDefinition item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null
                        : item.name + (item.id == null ? " (unsaved)" : item.enabled ? "" : " (off)"));
            }
        });
        skillList.getSelectionModel().selectedItemProperty().addListener((obs, old, now) -> {
            if (updating || now == editing) {
                return;
            }
            if (!confirmLeave()) {
                updating = true;
                skillList.getSelectionModel().select(editing);
                updating = false;
                return;
            }
            show(now);
        });
        enabledCheck.selectedProperty().addListener((obs, o, n) -> updateDirty());
        nameField.textProperty().addListener((obs, o, n) -> updateDirty());
        descriptionField.textProperty().addListener((obs, o, n) -> updateDirty());
        triggersField.textProperty().addListener((obs, o, n) -> updateDirty());
        instructionsArea.textProperty().addListener((obs, o, n) -> updateDirty());
        saveButton.disableProperty().bind(dirty.not());
        revertButton.disableProperty().bind(dirty.not());
    }

    /** Skills aren't part of the settings draft; opening the window (or Reset) just re-reads the folder. */
    @Override
    public void load(HarnessSettings settings) {
        refresh(null);
    }

    @Override
    public void apply(HarnessSettings settings) {
        // Saved per skill via "Save skill"; nothing to write into settings.json.
    }

    @FXML
    private void onRefresh() {
        if (confirmLeave()) {
            refresh(editing == null ? null : editing.id);
        }
    }

    private void refresh(String selectId) {
        List<SkillDefinition> loaded = repository.reload();
        updating = true;
        skills.setAll(loaded);
        updating = false;
        SkillDefinition select = loaded.stream().filter(s -> s.id.equals(selectId)).findFirst()
                .orElse(loaded.isEmpty() ? null : loaded.get(0));
        show(select);
        updating = true;
        skillList.getSelectionModel().select(select);
        updating = false;
        List<String> problems = repository.problems();
        statusLabel.setText(loaded.size() + (loaded.size() == 1 ? " skill" : " skills") + " loaded"
                + (problems.isEmpty() ? "" : "; skipped " + problems.size() + ": " + String.join("; ", problems)));
        statusLabel.getStyleClass().removeAll("status-error");
        if (!problems.isEmpty()) {
            statusLabel.getStyleClass().add("status-error");
        }
    }

    @FXML
    private void onAdd() {
        if (!confirmLeave()) {
            return;
        }
        SkillDefinition skill = new SkillDefinition("New skill", "", "", List.of(), true);
        skills.add(skill);
        show(skill);
        updating = true;
        skillList.getSelectionModel().select(skill);
        updating = false;
        nameField.requestFocus();
        nameField.selectAll();
    }

    @FXML
    private void onRemove() {
        SkillDefinition selected = editing;
        if (selected == null) {
            return;
        }
        if (selected.id == null) {
            discardUnsaved();
            return;
        }
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
                "Delete the skill '" + selected.name + "' (" + repository.file(selected.id) + ")?",
                ButtonType.OK, ButtonType.CANCEL);
        confirm.initOwner(root.getScene().getWindow());
        confirm.setHeaderText("Delete skill");
        if (confirm.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) {
            return;
        }
        try {
            repository.delete(selected.id);
        } catch (IOException | RuntimeException e) {
            LOG.error("Could not delete skill {}", selected.id, e);
            error("Could not delete the skill", e);
        }
        editing = null;
        refresh(null);
    }

    @FXML
    private void onSave() {
        save();
    }

    private boolean save() {
        if (editing == null) {
            return true;
        }
        try {
            SkillDefinition saved = repository.save(fromForm());
            editing = null;
            refresh(saved.id);
            return true;
        } catch (IOException | RuntimeException e) {
            LOG.warn("Could not save skill '{}': {}", nameField.getText(), e.getMessage());
            error("Could not save the skill", e);
            return false;
        }
    }

    @FXML
    private void onRevert() {
        if (editing != null && editing.id == null) {
            discardUnsaved();
        } else {
            show(editing);
        }
    }

    private void discardUnsaved() {
        SkillDefinition unsaved = editing;
        editing = null;
        updating = true;
        skills.remove(unsaved);
        updating = false;
        SkillDefinition first = skills.isEmpty() ? null : skills.get(0);
        show(first);
        updating = true;
        skillList.getSelectionModel().select(first);
        updating = false;
    }

    /** @return {@code true} if it's fine to replace the form (no changes, saved, or discarded). */
    private boolean confirmLeave() {
        if (!dirty.get()) {
            if (editing != null && editing.id == null) {
                discardUnsaved();
            }
            return true;
        }
        ButtonType save = new ButtonType("Save", ButtonData.YES);
        ButtonType discard = new ButtonType("Discard", ButtonData.NO);
        Alert ask = new Alert(Alert.AlertType.CONFIRMATION,
                "Save changes to the skill '" + editing.name + "'?", save, discard, ButtonType.CANCEL);
        ask.initOwner(root.getScene().getWindow());
        ask.setHeaderText("Unsaved skill");
        ButtonType answer = ask.showAndWait().orElse(ButtonType.CANCEL);
        if (answer == save) {
            return save();
        }
        if (answer == discard) {
            if (editing.id == null) {
                discardUnsaved();
            } else {
                show(editing);
            }
            return true;
        }
        return false;
    }

    private void show(SkillDefinition skill) {
        editing = skill;
        updating = true;
        form.setDisable(skill == null);
        deleteButton.setDisable(skill == null);
        enabledCheck.setSelected(skill != null && skill.enabled);
        nameField.setText(skill == null ? "" : skill.name);
        descriptionField.setText(skill == null ? "" : skill.description);
        triggersField.setText(skill == null ? "" : String.join(", ", skill.triggers));
        instructionsArea.setText(skill == null ? "" : skill.instructions);
        fileLabel.setText(skill == null ? "" : skill.id == null ? "Not saved yet"
                : repository.file(skill.id).toString());
        updating = false;
        updateDirty();
    }

    private SkillDefinition fromForm() {
        SkillDefinition skill = new SkillDefinition(nameField.getText().trim(), descriptionField.getText().trim(),
                instructionsArea.getText(), parseTriggers(triggersField.getText()), enabledCheck.isSelected());
        skill.id = editing == null ? null : editing.id;
        return skill;
    }

    private void updateDirty() {
        if (updating) {
            return;
        }
        if (editing == null) {
            dirty.set(false);
            return;
        }
        SkillDefinition form = fromForm();
        dirty.set(editing.id == null
                || form.enabled != editing.enabled
                || !form.name.equals(editing.name)
                || !form.description.equals(Objects.requireNonNullElse(editing.description, ""))
                || !form.triggers.equals(editing.triggers)
                || !form.instructions.strip().equals(Objects.requireNonNullElse(editing.instructions, "").strip()));
    }

    static List<String> parseTriggers(String text) {
        return Arrays.stream(text.split(",")).map(String::trim).filter(t -> !t.isEmpty()).distinct().toList();
    }

    private void error(String header, Exception e) {
        Alert alert = new Alert(Alert.AlertType.ERROR, e.getMessage(), ButtonType.OK);
        alert.initOwner(root.getScene().getWindow());
        alert.setHeaderText(header);
        alert.showAndWait();
    }
}
