package net.talaatharb.harness.ui.sections;

import javafx.collections.FXCollections;
import javafx.fxml.FXML;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.Spinner;
import javafx.scene.layout.VBox;
import net.talaatharb.harness.config.HarnessSettings;
import net.talaatharb.harness.reply.DynamicUiRenderer;
import net.talaatharb.harness.reply.ReplyFormat;
import net.talaatharb.harness.reply.ReplySegment;
import net.talaatharb.harness.reply.ReplySettings;

public class ReplySectionController implements SettingsSection {
    static final String SAMPLE = """
            {"type":"card","text":"Build status","children":[
              {"type":"row","children":[{"type":"badge","text":"main"},{"type":"badge","text":"passing"}]},
              {"type":"progress","text":"Coverage","value":0.72},
              {"type":"table","columns":["Module","Tests"],"rows":[["agent","12"],["tools","5"]]},
              {"type":"button","text":"Re-run","action":"Re-run the build"}]}""";

    @FXML private ComboBox<ReplySettings.Mode> modeCombo;
    @FXML private CheckBox traceCheck;
    @FXML private CheckBox thinkingCheck;
    @FXML private CheckBox contextCheck;
    @FXML private Spinner<Integer> listSpinner;
    @FXML private Spinner<Integer> bulletsSpinner;
    @FXML private Spinner<Integer> repairSpinner;
    @FXML private TextField allowedField;
    @FXML private TextArea specArea;
    @FXML private VBox previewBox;

    @FXML
    private void initialize() {
        modeCombo.setItems(FXCollections.observableArrayList(ReplySettings.Mode.values()));
        specArea.setText(SAMPLE);
    }

    @Override
    public void load(HarnessSettings s) {
        modeCombo.setValue(s.reply.mode);
        traceCheck.setSelected(s.reply.showToolTrace);
        thinkingCheck.setSelected(s.reply.showThinking);
        contextCheck.setSelected(s.reply.showContext);
        SettingsSection.intSpinner(listSpinner, 0, 100, s.reply.exactListItems);
        SettingsSection.intSpinner(bulletsSpinner, 0, 100, s.reply.maxBulletPoints);
        SettingsSection.intSpinner(repairSpinner, 0, 3, s.reply.maxRepairAttempts);
        allowedField.setText(s.reply.allowedReplies);
    }

    @Override
    public void apply(HarnessSettings s) {
        s.reply.mode = modeCombo.getValue();
        s.reply.showToolTrace = traceCheck.isSelected();
        s.reply.showThinking = thinkingCheck.isSelected();
        s.reply.showContext = contextCheck.isSelected();
        s.reply.exactListItems = SettingsSection.spinnerValue(listSpinner);
        s.reply.maxBulletPoints = SettingsSection.spinnerValue(bulletsSpinner);
        s.reply.maxRepairAttempts = SettingsSection.spinnerValue(repairSpinner);
        s.reply.allowedReplies = allowedField.getText();
    }

    @FXML
    private void onPreview() {
        previewBox.getChildren().clear();
        String reply = "```" + ReplyFormat.FENCE + "\n" + specArea.getText() + "\n```";
        for (ReplySegment segment : ReplyFormat.parse(reply, ReplySettings.Mode.AGENTIC_UI)) {
            if (segment instanceof ReplySegment.Ui ui) {
                previewBox.getChildren().add(DynamicUiRenderer.render(ui.component(),
                        action -> previewBox.getChildren().add(new Label("action → " + action))));
            } else if (segment instanceof ReplySegment.Invalid invalid) {
                previewBox.getChildren().add(new Label("Invalid spec: " + invalid.error()));
            }
        }
    }
}
