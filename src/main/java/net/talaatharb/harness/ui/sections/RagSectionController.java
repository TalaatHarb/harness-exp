package net.talaatharb.harness.ui.sections;

import java.util.List;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.Spinner;
import javafx.scene.control.TextField;
import javafx.scene.layout.VBox;
import javafx.util.StringConverter;
import net.talaatharb.harness.config.HarnessSettings;
import net.talaatharb.harness.rag.Embedder;
import net.talaatharb.harness.rag.OllamaEmbedder;
import net.talaatharb.harness.rag.RagService;
import net.talaatharb.harness.rag.RagSettings;
import net.talaatharb.harness.rag.RagSettings.EmbedderType;

/** Embedding model, chunking and retrieval settings for the per-conversation documents. */
public class RagSectionController implements SettingsSection {
    private static final Logger LOG = LoggerFactory.getLogger(RagSectionController.class);

    @FXML private VBox root;
    @FXML private CheckBox autoContextCheck;
    @FXML private ComboBox<EmbedderType> embedderCombo;
    @FXML private Label modelLabel;
    @FXML private TextField modelField;
    @FXML private Label baseUrlLabel;
    @FXML private TextField baseUrlField;
    @FXML private Label apiKeyLabel;
    @FXML private PasswordField apiKeyField;
    @FXML private Button testButton;
    @FXML private Label testLabel;
    @FXML private Spinner<Integer> chunkSizeSpinner;
    @FXML private Spinner<Integer> overlapSpinner;
    @FXML private Spinner<Integer> contextSpinner;
    @FXML private Spinner<Integer> contextCharsSpinner;
    @FXML private Spinner<Integer> searchSpinner;
    @FXML private Spinner<Integer> minScoreSpinner;
    @FXML private Spinner<Integer> fileMbSpinner;

    private Supplier<HarnessSettings> draft;
    private boolean loading;

    @FXML
    private void initialize() {
        embedderCombo.setItems(FXCollections.observableArrayList(EmbedderType.values()));
        embedderCombo.setConverter(new StringConverter<>() {
            @Override
            public String toString(EmbedderType type) {
                return type == null ? "" : switch (type) {
                    case OLLAMA -> "Ollama embedding model";
                    case OPENAI -> "OpenAI-compatible (cloud)";
                    case BUILT_IN -> "Built-in (offline, keywords)";
                };
            }

            @Override
            public EmbedderType fromString(String s) {
                return null;
            }
        });
        embedderCombo.valueProperty().addListener((o, a, b) -> {
            if (!loading) {
                if (b == EmbedderType.OPENAI && RagSettings.DEFAULT_OLLAMA_MODEL.equals(modelField.getText())) {
                    modelField.setText(RagSettings.DEFAULT_OPENAI_MODEL);
                } else if (b == EmbedderType.OLLAMA
                        && RagSettings.DEFAULT_OPENAI_MODEL.equals(modelField.getText())) {
                    modelField.setText(RagSettings.DEFAULT_OLLAMA_MODEL);
                }
            }
            updateEnabled();
        });
    }

    private void updateEnabled() {
        boolean remote = embedderCombo.getValue() != EmbedderType.BUILT_IN;
        boolean cloud = embedderCombo.getValue() == EmbedderType.OPENAI;
        for (var node : List.of(modelLabel, modelField, baseUrlLabel, baseUrlField, apiKeyLabel, apiKeyField)) {
            node.setDisable(!remote);
        }
        baseUrlLabel.setText(cloud ? "API base URL" : "Ollama URL");
        baseUrlField.setPromptText(cloud ? "blank = https://api.openai.com/v1"
                : "blank = the Model tab's Ollama URL, else http://localhost:11434");
        apiKeyField.setPromptText(cloud ? "blank = OPENAI_API_KEY environment variable"
                : "blank = Model tab's Ollama key or OLLAMA_API_KEY");
        modelField.setPromptText(cloud ? RagSettings.DEFAULT_OPENAI_MODEL : RagSettings.DEFAULT_OLLAMA_MODEL);
    }

    @Override
    public void setDraftSource(Supplier<HarnessSettings> source) {
        this.draft = source;
    }

    @Override
    public void load(HarnessSettings settings) {
        RagSettings s = settings.rag == null ? new RagSettings() : settings.rag;
        autoContextCheck.setSelected(s.autoContext);
        loading = true;
        embedderCombo.setValue(s.embedder == null ? EmbedderType.OLLAMA : s.embedder);
        loading = false;
        modelField.setText(s.embeddingModel);
        baseUrlField.setText(s.embeddingBaseUrl);
        apiKeyField.setText(s.embeddingApiKey);
        SettingsSection.intSpinner(chunkSizeSpinner, 200, 8000, s.chunkSize);
        SettingsSection.intSpinner(overlapSpinner, 0, 2000, s.chunkOverlap);
        SettingsSection.intSpinner(contextSpinner, 0, 20, s.contextResults);
        SettingsSection.intSpinner(contextCharsSpinner, 500, 50000, s.maxContextChars);
        SettingsSection.intSpinner(searchSpinner, 1, 50, s.maxSearchResults);
        SettingsSection.intSpinner(minScoreSpinner, 0, 95, s.minScorePercent);
        SettingsSection.intSpinner(fileMbSpinner, 1, 500, s.maxFileMb);
        testLabel.setText("");
        updateEnabled();
    }

    @Override
    public void apply(HarnessSettings settings) {
        if (settings.rag == null) {
            settings.rag = new RagSettings();
        }
        RagSettings s = settings.rag;
        s.autoContext = autoContextCheck.isSelected();
        s.embedder = embedderCombo.getValue() == null ? EmbedderType.OLLAMA : embedderCombo.getValue();
        String model = modelField.getText() == null ? "" : modelField.getText().strip();
        s.embeddingModel = model.isEmpty() ? (s.embedder == EmbedderType.OPENAI ? RagSettings.DEFAULT_OPENAI_MODEL
                : RagSettings.DEFAULT_OLLAMA_MODEL) : model;
        s.embeddingBaseUrl = baseUrlField.getText() == null ? "" : baseUrlField.getText().strip();
        s.embeddingApiKey = apiKeyField.getText() == null ? "" : apiKeyField.getText().strip();
        s.chunkSize = SettingsSection.spinnerValue(chunkSizeSpinner);
        s.chunkOverlap = Math.min(SettingsSection.spinnerValue(overlapSpinner), s.chunkSize / 2);
        s.contextResults = SettingsSection.spinnerValue(contextSpinner);
        s.maxContextChars = SettingsSection.spinnerValue(contextCharsSpinner);
        s.maxSearchResults = SettingsSection.spinnerValue(searchSpinner);
        s.minScorePercent = SettingsSection.spinnerValue(minScoreSpinner);
        s.maxFileMb = SettingsSection.spinnerValue(fileMbSpinner);
    }

    @FXML
    private void onTest() {
        HarnessSettings settings = draft != null ? draft.get() : new HarnessSettings();
        apply(settings);
        Embedder embedder;
        try {
            embedder = RagService.embedderFor(settings);
        } catch (IllegalArgumentException e) {
            LOG.warn("Invalid embedding configuration");
            testLabel.setText("Failed: check the embedding URL and model settings.");
            return;
        }
        String where = embedder instanceof OllamaEmbedder o ? " at " + o.baseUrl() : "";
        testButton.setDisable(true);
        testLabel.setText("Testing " + embedder.id() + where + "…");
        Thread worker = new Thread(() -> {
            String message;
            try {
                long start = System.currentTimeMillis();
                List<float[]> v = embedder.embed(List.of("The cat sat on the mat.", "A kitten rests on a rug."));
                message = String.format("OK: %s returned %d-dimensional vectors in %d ms (similarity of two "
                        + "related sentences: %.2f).", embedder.id(), v.get(0).length,
                        System.currentTimeMillis() - start, Embedder.cosine(v.get(0), v.get(1)));
            } catch (Exception e) {
                LOG.warn("Embedding test with {} failed", embedder.id(), e);
                message = "Failed: " + e.getMessage();
            }
            String text = message;
            Platform.runLater(() -> {
                testLabel.setText(text);
                testButton.setDisable(false);
            });
        }, "rag-embed-test");
        worker.setDaemon(true);
        worker.start();
    }
}
