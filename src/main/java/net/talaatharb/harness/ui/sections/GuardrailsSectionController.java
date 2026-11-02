package net.talaatharb.harness.ui.sections;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

import com.google.inject.Inject;

import javafx.fxml.FXML;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Spinner;
import javafx.scene.control.TextArea;
import javafx.scene.layout.VBox;
import net.talaatharb.harness.config.HarnessSettings;
import net.talaatharb.harness.guardrails.Guardrail;
import net.talaatharb.harness.guardrails.GuardrailService;

public class GuardrailsSectionController implements SettingsSection {
    private final GuardrailService guardrails;
    private final Map<String, CheckBox> toggles = new LinkedHashMap<>();

    @FXML private CheckBox enabledCheck;
    @FXML private VBox guardrailsBox;
    @FXML private Spinner<Integer> maxInputSpinner;
    @FXML private TextArea blockedTermsArea;

    @Inject
    public GuardrailsSectionController(GuardrailService guardrails) {
        this.guardrails = guardrails;
    }

    @FXML
    private void initialize() {
        for (Guardrail g : guardrails.all()) {
            CheckBox box = new CheckBox(g.id() + " " + g.stages() + " — " + g.description());
            box.disableProperty().bind(enabledCheck.selectedProperty().not());
            toggles.put(g.id(), box);
            guardrailsBox.getChildren().add(box);
        }
    }

    @Override
    public void load(HarnessSettings s) {
        enabledCheck.setSelected(s.guardrails.enabled);
        toggles.forEach((id, box) -> box.setSelected(!s.guardrails.disabledGuardrails.contains(id)));
        SettingsSection.intSpinner(maxInputSpinner, 0, 1_000_000, s.guardrails.maxInputChars);
        blockedTermsArea.setText(String.join("\n", s.guardrails.blockedTerms));
    }

    @Override
    public void apply(HarnessSettings s) {
        s.guardrails.enabled = enabledCheck.isSelected();
        s.guardrails.disabledGuardrails.clear();
        toggles.forEach((id, box) -> {
            if (!box.isSelected()) {
                s.guardrails.disabledGuardrails.add(id);
            }
        });
        s.guardrails.maxInputChars = SettingsSection.spinnerValue(maxInputSpinner);
        s.guardrails.blockedTerms = new java.util.ArrayList<>(Arrays.stream(blockedTermsArea.getText().split("\\R"))
                .map(String::trim).filter(t -> !t.isEmpty()).toList());
    }
}
