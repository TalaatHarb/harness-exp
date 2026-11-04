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
import net.talaatharb.harness.context.ContextBuild;
import net.talaatharb.harness.context.ContextBuilder;
import net.talaatharb.harness.context.ContextSection;
import net.talaatharb.harness.context.ContextContributor;
import net.talaatharb.harness.llm.ChatMessage;

public class ContextSectionController implements SettingsSection {
    private final ContextBuilder builder;
    private final Map<String, CheckBox> toggles = new LinkedHashMap<>();
    private Supplier<HarnessSettings> draftSource;

    @FXML private VBox contributorsBox;
    @FXML private Spinner<Integer> historySpinner;
    @FXML private CheckBox compactionCheck;
    @FXML private Spinner<Integer> compactionBudgetSpinner;
    @FXML private Spinner<Integer> compactionKeepSpinner;
    @FXML private Spinner<Integer> compactionWordsSpinner;
    @FXML private Spinner<Integer> maxAttachmentsSpinner;
    @FXML private Spinner<Integer> maxAttachmentMbSpinner;
    @FXML private Spinner<Integer> maxAttachmentTextSpinner;
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
        compactionCheck.setSelected(s.context.compactionEnabled);
        SettingsSection.intSpinner(compactionBudgetSpinner, 0, 1_000_000, s.context.compactionTokenBudget);
        SettingsSection.intSpinner(compactionKeepSpinner, 1, 100, s.context.compactionKeepRecent);
        SettingsSection.intSpinner(compactionWordsSpinner, 50, 2_000, s.context.compactionSummaryWords);
        SettingsSection.intSpinner(maxAttachmentsSpinner, 1, 50, s.context.maxAttachments);
        SettingsSection.intSpinner(maxAttachmentMbSpinner, 1, 200, s.context.maxAttachmentMb);
        SettingsSection.intSpinner(maxAttachmentTextSpinner, 1_000, 2_000_000, s.context.maxAttachmentTextChars);
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
        s.context.compactionEnabled = compactionCheck.isSelected();
        s.context.compactionTokenBudget = SettingsSection.spinnerValue(compactionBudgetSpinner);
        s.context.compactionKeepRecent = SettingsSection.spinnerValue(compactionKeepSpinner);
        s.context.compactionSummaryWords = SettingsSection.spinnerValue(compactionWordsSpinner);
        s.context.maxAttachments = SettingsSection.spinnerValue(maxAttachmentsSpinner);
        s.context.maxAttachmentMb = SettingsSection.spinnerValue(maxAttachmentMbSpinner);
        s.context.maxAttachmentTextChars = SettingsSection.spinnerValue(maxAttachmentTextSpinner);
        s.context.pinnedContext = pinnedArea.getText();
    }

    @FXML
    private void onPreview() {
        HarnessSettings draft = draftSource != null ? draftSource.get() : new HarnessSettings();
        ContextBuild build = builder.buildDetailed(draft, List.of(ChatMessage.user(sampleField.getText())), null);
        StringBuilder sb = new StringBuilder("=== Size by contributor (estimated) ===\n");
        for (ContextSection section : build.sections()) {
            sb.append(String.format(java.util.Locale.ROOT, "%-22s ~%6d tokens  %8d chars%n", section.contributor(),
                    section.estimatedTokens(), section.chars()));
        }
        sb.append("Total: ~").append(build.estimatedTokens()).append(" tokens\n\n");
        for (ChatMessage m : build.messages()) {
            sb.append("=== ").append(m.role()).append(" ===\n").append(m.contentForModel()).append("\n\n");
        }
        previewArea.setText(sb.toString());
    }
}
