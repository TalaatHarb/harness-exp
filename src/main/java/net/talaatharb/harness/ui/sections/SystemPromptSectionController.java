package net.talaatharb.harness.ui.sections;

import javafx.fxml.FXML;
import javafx.scene.control.TextArea;
import net.talaatharb.harness.config.HarnessSettings;
import net.talaatharb.harness.prompt.PromptTemplate;
import net.talaatharb.harness.prompt.SystemPromptSettings;

public class SystemPromptSectionController implements SettingsSection {
    @FXML private TextArea templateArea;
    @FXML private TextArea previewArea;
    private String model = "";

    @Override
    public void load(HarnessSettings s) {
        templateArea.setText(s.systemPrompt.template);
        model = s.model.model;
        previewArea.clear();
    }

    @Override
    public void apply(HarnessSettings s) {
        s.systemPrompt.template = templateArea.getText();
    }

    @FXML
    private void onReset() {
        templateArea.setText(SystemPromptSettings.DEFAULT_TEMPLATE);
    }

    @FXML
    private void onPreview() {
        previewArea.setText(PromptTemplate.render(templateArea.getText(), PromptTemplate.standardVariables(model)));
    }
}
