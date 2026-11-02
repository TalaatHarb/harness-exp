package net.talaatharb.harness.ui.sections;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

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
import javafx.scene.control.ProgressBar;
import javafx.scene.control.Spinner;
import net.talaatharb.harness.config.HarnessSettings;
import net.talaatharb.harness.speech.ProgressListener;
import net.talaatharb.harness.speech.SpeechSettings;
import net.talaatharb.harness.speech.SpeechToText;
import net.talaatharb.harness.speech.WhistleSpeechToText;

/** Voice input (dictation) and transcription of audio attachments. */
public class SpeechSectionController implements SettingsSection {
    private static final Logger LOG = LoggerFactory.getLogger(SpeechSectionController.class);

    private final SpeechToText speech;
    private final AtomicBoolean cancel = new AtomicBoolean();

    @FXML private CheckBox dictationCheck;
    @FXML private CheckBox sendAfterCheck;
    @FXML private CheckBox transcribeCheck;
    @FXML private ComboBox<String> languageCombo;
    @FXML private Spinner<Integer> maxSecondsSpinner;
    @FXML private Label engineLabel;
    @FXML private Label statusLabel;
    @FXML private Button downloadButton;
    @FXML private ProgressBar downloadProgress;

    @Inject
    public SpeechSectionController(SpeechToText speech) {
        this.speech = speech;
    }

    @FXML
    private void initialize() {
        languageCombo.setItems(FXCollections.observableArrayList(SpeechSettings.LANGUAGES));
        SettingsSection.intSpinner(maxSecondsSpinner, 5, SpeechSettings.MAX_RECORDING_SECONDS_LIMIT, 120);
        sendAfterCheck.disableProperty().bind(dictationCheck.selectedProperty().not());
        engineLabel.setText(speech.displayName()
                + (speech instanceof WhistleSpeechToText w ? " — " + w.directory() : ""));
        downloadProgress.setVisible(false);
        downloadProgress.managedProperty().bind(downloadProgress.visibleProperty());
        refreshStatus();
    }

    private void refreshStatus() {
        if (!speech.isSupported()) {
            statusLabel.setText("Not available on " + System.getProperty("os.name") + " / "
                    + System.getProperty("os.arch"));
            downloadButton.setDisable(true);
        } else if (speech.isInstalled()) {
            statusLabel.setText("Model installed ✓");
            downloadButton.setDisable(true);
        } else {
            statusLabel.setText("Model not downloaded yet (~18 MB, fetched automatically on first use)");
            downloadButton.setDisable(false);
        }
    }

    @Override
    public void load(HarnessSettings s) {
        SpeechSettings sp = s.speech == null ? new SpeechSettings() : s.speech;
        dictationCheck.setSelected(sp.dictationEnabled);
        sendAfterCheck.setSelected(sp.sendAfterDictation);
        transcribeCheck.setSelected(sp.transcribeAudioAttachments);
        languageCombo.setValue(SpeechSettings.LANGUAGES.contains(sp.language) ? sp.language : SpeechSettings.AUTO);
        maxSecondsSpinner.getValueFactory().setValue(
                Math.max(5, Math.min(SpeechSettings.MAX_RECORDING_SECONDS_LIMIT, sp.maxRecordingSeconds)));
    }

    @Override
    public void apply(HarnessSettings s) {
        if (s.speech == null) {
            s.speech = new SpeechSettings();
        }
        s.speech.dictationEnabled = dictationCheck.isSelected();
        s.speech.sendAfterDictation = sendAfterCheck.isSelected();
        s.speech.transcribeAudioAttachments = transcribeCheck.isSelected();
        s.speech.language = languageCombo.getValue() == null ? SpeechSettings.AUTO : languageCombo.getValue();
        s.speech.maxRecordingSeconds = SettingsSection.spinnerValue(maxSecondsSpinner);
    }

    @FXML
    private void onDownload() {
        downloadButton.setDisable(true);
        downloadProgress.setVisible(true);
        downloadProgress.setProgress(ProgressBar.INDETERMINATE_PROGRESS);
        cancel.set(false);
        ProgressListener progress = new ProgressListener() {
            @Override
            public void update(String message, double fraction) {
                Platform.runLater(() -> {
                    statusLabel.setText(message);
                    downloadProgress.setProgress(fraction < 0 ? ProgressBar.INDETERMINATE_PROGRESS : fraction);
                });
            }

            @Override
            public boolean isCancelled() {
                return cancel.get();
            }
        };
        CompletableFuture.runAsync(() -> {
            try {
                speech.install(progress);
                Platform.runLater(() -> {
                    downloadProgress.setVisible(false);
                    refreshStatus();
                });
            } catch (Exception e) {
                LOG.error("Speech model download failed", e);
                Platform.runLater(() -> {
                    downloadProgress.setVisible(false);
                    refreshStatus();
                    statusLabel.setText("Download failed: " + e.getMessage());
                });
            }
        });
    }
}
