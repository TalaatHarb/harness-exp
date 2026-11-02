package net.talaatharb.harness.ui.sections;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.fxml.FXML;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ListView;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.VBox;
import net.talaatharb.harness.config.HarnessSettings;
import net.talaatharb.harness.skills.SkillDefinition;

public class SkillsSectionController implements SettingsSection {
    private final ObservableList<SkillDefinition> skills = FXCollections.observableArrayList();
    private SkillDefinition editing;

    @FXML private ListView<SkillDefinition> skillList;
    @FXML private VBox form;
    @FXML private CheckBox enabledCheck;
    @FXML private TextField nameField;
    @FXML private TextField descriptionField;
    @FXML private TextField triggersField;
    @FXML private TextArea instructionsArea;

    @FXML
    private void initialize() {
        skillList.setItems(skills);
        skillList.getSelectionModel().selectedItemProperty().addListener((obs, old, now) -> {
            commitForm();
            show(now);
        });
    }

    @Override
    public void load(HarnessSettings s) {
        editing = null;
        skills.clear();
        for (SkillDefinition d : s.skills) {
            skills.add(new SkillDefinition(d.name, d.description, d.instructions, d.triggers, d.enabled));
        }
        if (skills.isEmpty()) {
            show(null);
        } else {
            skillList.getSelectionModel().selectFirst();
        }
    }

    @Override
    public void apply(HarnessSettings s) {
        commitForm();
        List<SkillDefinition> out = new ArrayList<>();
        for (SkillDefinition d : skills) {
            out.add(new SkillDefinition(d.name, d.description, d.instructions, d.triggers, d.enabled));
        }
        s.skills = out;
    }

    @FXML
    private void onAdd() {
        SkillDefinition skill = new SkillDefinition("New skill", "", "", List.of(), true);
        skills.add(skill);
        skillList.getSelectionModel().select(skill);
    }

    @FXML
    private void onRemove() {
        SkillDefinition selected = skillList.getSelectionModel().getSelectedItem();
        if (selected != null) {
            editing = null;
            skills.remove(selected);
        }
    }

    private void show(SkillDefinition skill) {
        editing = skill;
        form.setDisable(skill == null);
        enabledCheck.setSelected(skill != null && skill.enabled);
        nameField.setText(skill == null ? "" : skill.name);
        descriptionField.setText(skill == null ? "" : skill.description);
        triggersField.setText(skill == null ? "" : String.join(", ", skill.triggers));
        instructionsArea.setText(skill == null ? "" : skill.instructions);
    }

    private void commitForm() {
        if (editing == null) {
            return;
        }
        editing.enabled = enabledCheck.isSelected();
        editing.name = nameField.getText().trim();
        editing.description = descriptionField.getText().trim();
        editing.triggers = Arrays.stream(triggersField.getText().split(",")).map(String::trim)
                .filter(t -> !t.isEmpty()).toList();
        editing.instructions = instructionsArea.getText();
        skillList.refresh();
    }
}
