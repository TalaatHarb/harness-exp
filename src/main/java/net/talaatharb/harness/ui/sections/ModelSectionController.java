package net.talaatharb.harness.ui.sections;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.inject.Inject;

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
import net.talaatharb.harness.config.HarnessSettings;
import net.talaatharb.harness.llm.ModelProvider;
import net.talaatharb.harness.llm.ModelProviderRegistry;
import net.talaatharb.harness.llm.ModelSettings;
import net.talaatharb.harness.llm.OllamaModelProvider;

public class ModelSectionController implements SettingsSection {
    private static final Logger LOG = LoggerFactory.getLogger(ModelSectionController.class);
    private final ModelProviderRegistry providers;

    @FXML private VBox root;
    @FXML private ComboBox<String> providerCombo;
    @FXML private TextField baseUrlField;
    @FXML private PasswordField apiKeyField;
    @FXML private ComboBox<String> modelCombo;
    @FXML private Spinner<Integer> timeoutSpinner;
    @FXML private CheckBox streamingCheck;
    @FXML private Spinner<Integer> maxTokensSpinner;
    @FXML private TextField temperatureField;
    @FXML private TextField seedField;
    @FXML private Label samplingLabel;
    @FXML private Button localButton;
    @FXML private Button cloudButton;
    @FXML private Button defaultUrlButton;
    @FXML private Label testLabel;

    @Inject
    public ModelSectionController(ModelProviderRegistry providers) {
        this.providers = providers;
    }

    @FXML
    private void initialize() {
        providerCombo.setItems(FXCollections.observableArrayList(
                providers.all().stream().map(ModelProvider::id).toList()));
        providerCombo.valueProperty().addListener((obs, old, now) -> onProviderChanged(old, now));
    }

    /** Swaps in the new provider's default URL unless the user typed a custom one, and updates hints. */
    private void onProviderChanged(String oldId, String newId) {
        ModelProvider provider = providers.find(newId).orElse(null);
        boolean ollama = OllamaModelProvider.ID.equals(newId);
        for (Button b : List.of(localButton, cloudButton)) {
            b.setVisible(ollama);
            b.setManaged(ollama);
        }
        defaultUrlButton.setVisible(!ollama);
        defaultUrlButton.setManaged(!ollama);
        samplingLabel.setText("anthropic".equals(newId)
                ? "Temperature: 0–1. Anthropic does not support seed; leave seed blank."
                : "openai".equals(newId)
                ? "Temperature: 0–2. Seed: signed 64-bit integer. Model/endpoint support varies."
                : "Temperature: ≥0. Seed: signed 32-bit integer. Seed reproducibility is best effort.");
        if (provider == null) {
            return;
        }
        String env = provider.apiKeyEnvVar();
        apiKeyField.setPromptText((ollama ? "optional locally, required for Ollama cloud" : "required")
                + (env == null ? "" : "; blank = $" + env));
        if (oldId != null && !oldId.equals(newId) && isDefaultUrl(baseUrlField.getText())) {
            baseUrlField.setText(provider.defaultBaseUrl());
            modelCombo.getItems().clear();
            testLabel.setText("Provider changed: pick a model (Test connection lists them).");
        }
    }

    private boolean isDefaultUrl(String url) {
        String u = url == null ? "" : url.trim();
        return u.isEmpty() || u.equals(ModelSettings.CLOUD_OLLAMA_URL)
                || providers.all().stream().anyMatch(p -> u.equals(p.defaultBaseUrl()));
    }

    public VBox root() {
        return root;
    }

    @Override
    public void load(HarnessSettings s) {
        providerCombo.setValue(s.model.provider);
        baseUrlField.setText(s.model.baseUrl);
        apiKeyField.setText(s.model.apiKey);
        modelCombo.setValue(s.model.model);
        SettingsSection.intSpinner(timeoutSpinner, 5, 3600, (int) s.model.requestTimeoutSeconds);
        streamingCheck.setSelected(s.model.streaming);
        SettingsSection.intSpinner(maxTokensSpinner, 1, 1_000_000, s.model.maxOutputTokens);
        temperatureField.setText(s.model.temperature == null ? "" : s.model.temperature.toString());
        seedField.setText(s.model.seed == null ? "" : s.model.seed.toString());
        onProviderChanged(null, s.model.provider);
    }

    @Override
    public void apply(HarnessSettings s) {
        ModelSettings sampling = new ModelSettings();
        sampling.temperature = ModelSettings.parseTemperature(temperatureField.getText());
        sampling.seed = ModelSettings.parseSeed(seedField.getText());
        try {
            sampling.validateSampling(providerCombo.getValue());
        } catch (net.talaatharb.harness.llm.ModelProviderException e) {
            throw new IllegalArgumentException(e.getMessage(), e);
        }
        s.model.provider = providerCombo.getValue();
        s.model.baseUrl = baseUrlField.getText().trim();
        s.model.apiKey = apiKeyField.getText();
        String model = modelCombo.getEditor().getText();
        s.model.model = model == null || model.isBlank() ? s.model.model : model.trim();
        s.model.requestTimeoutSeconds = SettingsSection.spinnerValue(timeoutSpinner);
        s.model.streaming = streamingCheck.isSelected();
        s.model.maxOutputTokens = SettingsSection.spinnerValue(maxTokensSpinner);
        s.model.temperature = sampling.temperature;
        s.model.seed = sampling.seed;
    }

    @FXML
    private void onDefaultUrl() {
        providers.find(providerCombo.getValue()).ifPresent(p -> baseUrlField.setText(p.defaultBaseUrl()));
    }

    @FXML
    private void onLocal() {
        baseUrlField.setText(ModelSettings.LOCAL_OLLAMA_URL);
    }

    @FXML
    private void onCloud() {
        baseUrlField.setText(ModelSettings.CLOUD_OLLAMA_URL);
    }

    @FXML
    private void onTest() {
        HarnessSettings probe = new HarnessSettings();
        try {
            apply(probe);
        } catch (IllegalArgumentException e) {
            testLabel.setText(e.getMessage());
            return;
        }
        testLabel.setText("testing…");
        CompletableFuture.runAsync(() -> {
            try {
                List<String> models = providers.get(probe.model).listModels(probe.model);
                Platform.runLater(() -> {
                    String current = modelCombo.getEditor().getText();
                    modelCombo.setItems(FXCollections.observableArrayList(models));
                    modelCombo.setValue(current);
                    testLabel.setText("OK – " + models.size() + " models available");
                });
            } catch (Exception e) {
                LOG.error("Model connection test to {} failed", probe.model.baseUrl, e);
                Platform.runLater(() -> testLabel.setText("Failed: " + e.getMessage()));
            }
        });
    }
}
