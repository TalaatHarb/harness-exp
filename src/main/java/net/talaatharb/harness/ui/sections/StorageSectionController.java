package net.talaatharb.harness.ui.sections;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

import com.google.inject.Inject;
import com.google.inject.name.Named;

import javafx.fxml.FXML;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.VBox;
import javafx.stage.DirectoryChooser;
import javafx.stage.FileChooser;
import net.talaatharb.harness.config.HarnessSettings;
import net.talaatharb.harness.config.SettingsStore;
import net.talaatharb.harness.config.StoragePaths;
import net.talaatharb.harness.config.StorageSettings;

/** Picks external locations for skills, dynamic tools and the history DB. They take effect after a restart. */
public class StorageSectionController implements SettingsSection {
    private final Path settingsPath;
    private final StoragePaths inUse;
    private final StoragePaths defaults;

    @FXML private VBox root;
    @FXML private TextField skillsField;
    @FXML private TextField toolsField;
    @FXML private TextField historyField;
    @FXML private Label skillsInUse;
    @FXML private Label toolsInUse;
    @FXML private Label historyInUse;
    @FXML private Label restartLabel;

    @Inject
    public StorageSectionController(@Named(SettingsStore.SETTINGS_PATH) Path settingsPath, StoragePaths inUse) {
        this.settingsPath = settingsPath;
        this.inUse = inUse;
        this.defaults = StoragePaths.defaults(settingsPath);
    }

    @FXML
    private void initialize() {
        skillsField.setPromptText(defaults.skillsDir() + "  (default)");
        toolsField.setPromptText(defaults.dynamicToolsDir() + "  (default)");
        historyField.setPromptText(defaults.historyDb() + "  (default)");
        skillsInUse.setText("In use: " + inUse.skillsDir());
        toolsInUse.setText("In use: " + inUse.dynamicToolsDir());
        historyInUse.setText("In use: " + inUse.historyDb());
        skillsField.textProperty().addListener((o, a, b) -> updateRestartNotice());
        toolsField.textProperty().addListener((o, a, b) -> updateRestartNotice());
        historyField.textProperty().addListener((o, a, b) -> updateRestartNotice());
    }

    @Override
    public void load(HarnessSettings settings) {
        StorageSettings s = settings.storage == null ? new StorageSettings() : settings.storage;
        skillsField.setText(s.skillsDir);
        toolsField.setText(s.dynamicToolsDir);
        historyField.setText(s.historyDb);
        updateRestartNotice();
    }

    @Override
    public void apply(HarnessSettings settings) {
        if (settings.storage == null) {
            settings.storage = new StorageSettings();
        }
        settings.storage.skillsDir = skillsField.getText().strip();
        settings.storage.dynamicToolsDir = toolsField.getText().strip();
        settings.storage.historyDb = historyField.getText().strip();
    }

    private StoragePaths pending() {
        StorageSettings s = new StorageSettings();
        s.skillsDir = skillsField.getText();
        s.dynamicToolsDir = toolsField.getText();
        s.historyDb = historyField.getText();
        return StoragePaths.resolve(settingsPath, s);
    }

    private void updateRestartNotice() {
        boolean changed = !pending().equals(inUse);
        restartLabel.setVisible(changed);
        restartLabel.setManaged(changed);
    }

    @FXML
    private void onBrowseSkills() {
        chooseFolder(skillsField, "Skills folder", pending().skillsDir());
    }

    @FXML
    private void onBrowseTools() {
        chooseFolder(toolsField, "Dynamic tools folder", pending().dynamicToolsDir());
    }

    @FXML
    private void onBrowseHistory() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Conversation history database");
        Path current = pending().historyDb();
        File folder = existingFolder(current.getParent());
        if (folder != null) {
            chooser.setInitialDirectory(folder);
        }
        chooser.setInitialFileName(current.getFileName().toString());
        chooser.getExtensionFilters().addAll(new FileChooser.ExtensionFilter("MapDB file (*.db)", "*.db"),
                new FileChooser.ExtensionFilter("All files", "*.*"));
        File file = chooser.showSaveDialog(root.getScene().getWindow());
        if (file != null) {
            historyField.setText(file.getAbsolutePath());
        }
    }

    @FXML
    private void onUseDefaults() {
        skillsField.clear();
        toolsField.clear();
        historyField.clear();
    }

    private void chooseFolder(TextField field, String title, Path current) {
        DirectoryChooser chooser = new DirectoryChooser();
        chooser.setTitle(title);
        File start = existingFolder(current);
        if (start == null) {
            start = existingFolder(current.getParent());
        }
        if (start != null) {
            chooser.setInitialDirectory(start);
        }
        File folder = chooser.showDialog(root.getScene().getWindow());
        if (folder != null) {
            field.setText(folder.getAbsolutePath());
        }
    }

    private static File existingFolder(Path path) {
        return path != null && Files.isDirectory(path) ? path.toFile() : null;
    }
}
