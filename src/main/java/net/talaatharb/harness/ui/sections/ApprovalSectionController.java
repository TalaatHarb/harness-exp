package net.talaatharb.harness.ui.sections;

import java.util.HashSet;

import javafx.collections.FXCollections;
import javafx.fxml.FXML;
import javafx.scene.control.ComboBox;
import javafx.scene.control.ListView;
import javafx.scene.control.Spinner;
import net.talaatharb.harness.approval.ApprovalPolicy;
import net.talaatharb.harness.config.HarnessSettings;

public class ApprovalSectionController implements SettingsSection {
    @FXML private ComboBox<ApprovalPolicy> policyCombo;
    @FXML private Spinner<Integer> timeoutSpinner;
    @FXML private ListView<String> allowedList;

    @FXML
    private void initialize() {
        policyCombo.setItems(FXCollections.observableArrayList(ApprovalPolicy.values()));
    }

    @Override
    public void load(HarnessSettings s) {
        policyCombo.setValue(s.approval.policy);
        SettingsSection.intSpinner(timeoutSpinner, 5, 3600, s.approval.timeoutSeconds);
        allowedList.setItems(FXCollections.observableArrayList(s.approval.alwaysAllowedTools.stream().sorted().toList()));
    }

    @Override
    public void apply(HarnessSettings s) {
        s.approval.policy = policyCombo.getValue();
        s.approval.timeoutSeconds = SettingsSection.spinnerValue(timeoutSpinner);
        s.approval.alwaysAllowedTools = new HashSet<>(allowedList.getItems());
    }

    @FXML
    private void onRevoke() {
        String selected = allowedList.getSelectionModel().getSelectedItem();
        if (selected != null) {
            allowedList.getItems().remove(selected);
        }
    }
}
