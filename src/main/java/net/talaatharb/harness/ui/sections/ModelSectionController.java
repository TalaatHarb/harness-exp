package net.talaatharb.harness.ui.sections;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.inject.Inject;

import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.fxml.FXML;
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
    @FXML private Label testLabel;

    @Inject
    public ModelSectionController(ModelProviderRegistry providers) {
        this.providers = providers;
    }

    @FXML
    private void initialize() {
        providerCombo.setItems(FXCollections.observableArrayList(
                providers.all().stream().map(ModelProvider::id).toList()));
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
    }

    @Override
    public void apply(HarnessSettings s) {
        s.model.provider = providerCombo.getValue();
        s.model.baseUrl = baseUrlField.getText().trim();
        s.model.apiKey = apiKeyField.getText();
        String model = modelCombo.getEditor().getText();
        s.model.model = model == null || model.isBlank() ? s.model.model : model.trim();
        s.model.requestTimeoutSeconds = SettingsSection.spinnerValue(timeoutSpinner);
        s.model.streaming = streamingCheck.isSelected();
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
        apply(probe);
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
