package net.talaatharb.harness.ui.sections;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import com.google.inject.Inject;

import javafx.fxml.FXML;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Spinner;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.VBox;
import net.talaatharb.harness.config.HarnessSettings;
import net.talaatharb.harness.context.ContextBuilder;
import net.talaatharb.harness.context.ContextContributor;
import net.talaatharb.harness.llm.ChatMessage;

public class ContextSectionController implements SettingsSection {
    private final ContextBuilder builder;
    private final Map<String, CheckBox> toggles = new LinkedHashMap<>();
    private Supplier<HarnessSettings> draftSource;

    @FXML private VBox contributorsBox;
    @FXML private Spinner<Integer> historySpinner;
    @FXML private TextArea pinnedArea;
    @FXML private TextField sampleField;
    @FXML private TextArea previewArea;

    @Inject
    public ContextSectionController(ContextBuilder builder) {
        this.builder = builder;
    }

    @FXML
    private void initialize() {
        for (ContextContributor c : builder.contributors()) {
            CheckBox box = new CheckBox(c.order() + "  " + c.id() + " — " + c.description());
            toggles.put(c.id(), box);
            contributorsBox.getChildren().add(box);
        }
    }

    @Override
    public void setDraftSource(Supplier<HarnessSettings> source) {
        draftSource = source;
    }

    @Override
    public void load(HarnessSettings s) {
        toggles.forEach((id, box) -> box.setSelected(!s.context.disabledContributors.contains(id)));
        SettingsSection.intSpinner(historySpinner, 1, 500, s.context.maxHistoryMessages);
        pinnedArea.setText(s.context.pinnedContext);
        previewArea.clear();
    }

    @Override
    public void apply(HarnessSettings s) {
        s.context.disabledContributors.clear();
        toggles.forEach((id, box) -> {
            if (!box.isSelected()) {
                s.context.disabledContributors.add(id);
            }
        });
        s.context.maxHistoryMessages = SettingsSection.spinnerValue(historySpinner);
        s.context.pinnedContext = pinnedArea.getText();
    }

    @FXML
    private void onPreview() {
        HarnessSettings draft = draftSource != null ? draftSource.get() : new HarnessSettings();
        List<ChatMessage> context = builder.build(draft, List.of(ChatMessage.user(sampleField.getText())));
        StringBuilder sb = new StringBuilder();
        for (ChatMessage m : context) {
            sb.append("=== ").append(m.role()).append(" ===\n").append(m.content()).append("\n\n");
        }
        previewArea.setText(sb.toString());
    }
}
