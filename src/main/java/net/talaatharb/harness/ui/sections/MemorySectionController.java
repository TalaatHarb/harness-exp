package net.talaatharb.harness.ui.sections;

import java.io.IOException;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.inject.Inject;

import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.fxml.FXML;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar.ButtonData;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.Spinner;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.VBox;
import net.talaatharb.harness.config.HarnessSettings;
import net.talaatharb.harness.memory.MemoryEntry;
import net.talaatharb.harness.memory.MemoryService;
import net.talaatharb.harness.memory.MemorySettings;
import net.talaatharb.harness.memory.MemoryStore;

/**
 * Browse, search, add, edit and delete memories of the backend chosen in the combo box. Memory edits are written to
 * the store straight away; the backend and limits are settings (Apply/Save).
 */
public class MemorySectionController implements SettingsSection {
    private static final Logger LOG = LoggerFactory.getLogger(MemorySectionController.class);
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
            .withZone(ZoneId.systemDefault());

    private final MemoryService memory;
    private final ObservableList<MemoryEntry> shown = FXCollections.observableArrayList();
    private final BooleanProperty dirty = new SimpleBooleanProperty();
    private List<MemoryEntry> all = List.of();
    private MemoryEntry editing;
    private boolean updating;

    @FXML private VBox root;
    @FXML private ComboBox<MemorySettings.Backend> backendCombo;
    @FXML private TextField locationField;
    @FXML private Spinner<Integer> contextSpinner;
    @FXML private Spinner<Integer> recallSpinner;
    @FXML private Spinner<Integer> duplicateSpinner;
    @FXML private TextField searchField;
    @FXML private Label statusLabel;
    @FXML private ListView<MemoryEntry> memoryList;
    @FXML private Button deleteButton;
    @FXML private Button copyButton;
    @FXML private VBox form;
    @FXML private TextArea textArea;
    @FXML private TextField tagsField;
    @FXML private Label infoLabel;
    @FXML private Button revertButton;
    @FXML private Button saveButton;

    @Inject
    public MemorySectionController(MemoryService memory) {
        this.memory = memory;
    }

    @FXML
    private void initialize() {
        backendCombo.getItems().setAll(MemorySettings.Backend.values());
        backendCombo.valueProperty().addListener((o, old, now) -> {
            if (updating || now == null || now == old) {
                return;
            }
            if (!confirmLeave()) {
                updating = true;
                backendCombo.setValue(old);
                updating = false;
                return;
            }
            editing = null;
            refresh(null);
        });
        memoryList.setItems(shown);
        memoryList.setPlaceholder(new Label("No memories. Add one, or let the agent use the remember tool."));
        memoryList.setCellFactory(list -> new ListCell<>() {
            @Override
            protected void updateItem(MemoryEntry item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                    return;
                }
                String meta = item.id == null ? "unsaved"
                        : (item.updatedAt == null ? "" : DATE.format(item.updatedAt)) + " · " + item.source
                                + (item.tags.isEmpty() ? "" : " · #" + String.join(" #", item.tags));
                setText(item.preview(70) + "\n" + meta);
            }
        });
        memoryList.getSelectionModel().selectedItemProperty().addListener((o, old, now) -> {
            if (updating || now == editing) {
                return;
            }
            if (!confirmLeave()) {
                select(editing);
                return;
            }
            show(now);
        });
        searchField.textProperty().addListener((o, a, b) -> filter());
        textArea.textProperty().addListener((o, a, b) -> updateDirty());
        tagsField.textProperty().addListener((o, a, b) -> updateDirty());
        saveButton.disableProperty().bind(dirty.not());
        revertButton.disableProperty().bind(dirty.not());
    }

    @Override
    public void load(HarnessSettings settings) {
        MemorySettings s = settings.memory == null ? new MemorySettings() : settings.memory;
        SettingsSection.intSpinner(contextSpinner, 0, 50, s.maxContextMemories);
        SettingsSection.intSpinner(recallSpinner, 1, 100, s.maxRecallResults);
        SettingsSection.intSpinner(duplicateSpinner, 30, 100, s.duplicateThresholdPercent);
        updating = true;
        backendCombo.setValue(s.backend);
        updating = false;
        editing = null;
        refresh(null);
    }

    @Override
    public void apply(HarnessSettings settings) {
        if (settings.memory == null) {
            settings.memory = new MemorySettings();
        }
        settings.memory.backend = backend();
        settings.memory.maxContextMemories = SettingsSection.spinnerValue(contextSpinner);
        settings.memory.maxRecallResults = SettingsSection.spinnerValue(recallSpinner);
        settings.memory.duplicateThresholdPercent = SettingsSection.spinnerValue(duplicateSpinner);
    }

    private MemorySettings.Backend backend() {
        return backendCombo.getValue() == null ? MemorySettings.Backend.MARKDOWN : backendCombo.getValue();
    }

    private MemorySettings.Backend otherBackend() {
        return backend() == MemorySettings.Backend.MARKDOWN ? MemorySettings.Backend.MAPDB
                : MemorySettings.Backend.MARKDOWN;
    }

    private MemoryStore store() {
        return memory.store(backend());
    }

    @FXML
    private void onRefresh() {
        if (confirmLeave()) {
            refresh(editing == null ? null : editing.id);
        }
    }

    private void refresh(String selectId) {
        try {
            all = store().list();
        } catch (RuntimeException e) {
            LOG.error("Could not read memories from {}", store().location(), e);
            all = List.of();
            statusLabel.setText("Could not read memories: " + e.getMessage());
        }
        locationField.setText(store().location());
        copyButton.setText("Copy all to " + otherBackend());
        copyButton.setDisable(all.isEmpty());
        filter();
        MemoryEntry select = shown.stream().filter(m -> Objects.equals(m.id, selectId)).findFirst()
                .orElse(shown.isEmpty() ? null : shown.get(0));
        show(select);
        select(select);
    }

    private void filter() {
        String query = searchField.getText() == null ? "" : searchField.getText().strip();
        List<MemoryEntry> result = query.isEmpty() ? all
                : MemoryService.rank(all, query, Math.max(1, all.size())).stream()
                        .filter(m -> m.score() > 0).map(MemoryService.Match::entry).toList();
        updating = true;
        shown.setAll(result);
        if (editing != null && editing.id == null) {
            shown.add(0, editing);
        }
        updating = false;
        statusLabel.setText(query.isEmpty() ? all.size() + (all.size() == 1 ? " memory" : " memories")
                : result.size() + " of " + all.size() + " match");
        if (editing != null && shown.contains(editing)) {
            select(editing);
        }
    }

    private void select(MemoryEntry entry) {
        updating = true;
        if (entry == null) {
            memoryList.getSelectionModel().clearSelection();
        } else {
            memoryList.getSelectionModel().select(entry);
        }
        updating = false;
    }

    @FXML
    private void onAdd() {
        if (!confirmLeave()) {
            return;
        }
        MemoryEntry entry = new MemoryEntry("", List.of(), MemoryEntry.SOURCE_USER);
        editing = entry;
        updating = true;
        shown.add(0, entry);
        updating = false;
        show(entry);
        select(entry);
        textArea.requestFocus();
    }

    @FXML
    private void onDelete() {
        MemoryEntry selected = editing;
        if (selected == null) {
            return;
        }
        if (selected.id == null) {
            discardUnsaved();
            return;
        }
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION, "Delete this memory?\n\n" + selected.preview(200),
                ButtonType.OK, ButtonType.CANCEL);
        confirm.initOwner(root.getScene().getWindow());
        confirm.setHeaderText("Delete memory");
        if (confirm.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) {
            return;
        }
        try {
            store().delete(selected.id);
        } catch (IOException | RuntimeException e) {
            LOG.error("Could not delete memory {}", selected.id, e);
            error("Could not delete the memory", e);
        }
        editing = null;
        refresh(null);
    }

    @FXML
    private void onSave() {
        save();
    }

    private boolean save() {
        if (editing == null) {
            return true;
        }
        String text = textArea.getText() == null ? "" : textArea.getText().strip();
        if (text.isEmpty()) {
            error("Could not save the memory", new IllegalArgumentException("The memory text is empty."));
            return false;
        }
        if (text.length() > MemoryService.MAX_MEMORY_CHARS) {
            error("Could not save the memory", new IllegalArgumentException("The memory is longer than "
                    + MemoryService.MAX_MEMORY_CHARS + " characters; split it into smaller facts."));
            return false;
        }
        MemoryEntry updated = editing.copy();
        updated.text = text;
        updated.tags = MemoryEntry.parseTags(tagsField.getText());
        updated.source = MemoryEntry.SOURCE_USER;
        try {
            MemoryEntry saved = store().save(updated);
            editing = null;
            refresh(saved.id);
            return true;
        } catch (IOException | RuntimeException e) {
            LOG.error("Could not save memory {}", updated.id, e);
            error("Could not save the memory", e);
            return false;
        }
    }

    @FXML
    private void onRevert() {
        if (editing != null && editing.id == null) {
            discardUnsaved();
        } else {
            show(editing);
        }
    }

    @FXML
    private void onCopyAll() {
        if (!confirmLeave()) {
            return;
        }
        MemoryStore target = memory.store(otherBackend());
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION, "Copy all " + all.size() + " memories to "
                + otherBackend() + " (" + target.location() + ")? Memories with the same id are overwritten.",
                ButtonType.OK, ButtonType.CANCEL);
        confirm.initOwner(root.getScene().getWindow());
        confirm.setHeaderText("Copy memories");
        if (confirm.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) {
            return;
        }
        int copied = 0;
        for (MemoryEntry e : all) {
            try {
                target.save(e);
                copied++;
            } catch (IOException | RuntimeException ex) {
                LOG.error("Could not copy memory {} to {}", e.id, target.location(), ex);
            }
        }
        statusLabel.setText("Copied " + copied + " of " + all.size() + " to " + otherBackend());
    }

    private void discardUnsaved() {
        MemoryEntry unsaved = editing;
        editing = null;
        updating = true;
        shown.remove(unsaved);
        updating = false;
        MemoryEntry first = shown.isEmpty() ? null : shown.get(0);
        show(first);
        select(first);
    }

    /** @return {@code true} if it's fine to replace the form (no changes, saved, or discarded). */
    private boolean confirmLeave() {
        if (!dirty.get()) {
            if (editing != null && editing.id == null) {
                discardUnsaved();
            }
            return true;
        }
        ButtonType save = new ButtonType("Save", ButtonData.YES);
        ButtonType discard = new ButtonType("Discard", ButtonData.NO);
        Alert ask = new Alert(Alert.AlertType.CONFIRMATION, "Save changes to this memory?", save, discard,
                ButtonType.CANCEL);
        ask.initOwner(root.getScene().getWindow());
        ask.setHeaderText("Unsaved memory");
        ButtonType answer = ask.showAndWait().orElse(ButtonType.CANCEL);
        if (answer == save) {
            return save();
        }
        if (answer == discard) {
            if (editing.id == null) {
                discardUnsaved();
            } else {
                show(editing);
            }
            return true;
        }
        return false;
    }

    private void show(MemoryEntry entry) {
        editing = entry;
        updating = true;
        form.setDisable(entry == null);
        deleteButton.setDisable(entry == null);
        textArea.setText(entry == null ? "" : entry.text);
        tagsField.setText(entry == null ? "" : String.join(", ", entry.tags));
        infoLabel.setText(entry == null ? "" : entry.id == null ? "Not saved yet"
                : "[" + entry.id + "] · " + entry.source + " · created "
                        + (entry.createdAt == null ? "?" : DATE.format(entry.createdAt)) + " · updated "
                        + (entry.updatedAt == null ? "?" : DATE.format(entry.updatedAt)));
        updating = false;
        updateDirty();
    }

    private void updateDirty() {
        if (updating) {
            return;
        }
        if (editing == null) {
            dirty.set(false);
            return;
        }
        String text = textArea.getText() == null ? "" : textArea.getText().strip();
        dirty.set(editing.id == null ? !text.isEmpty()
                : !text.equals(Objects.requireNonNullElse(editing.text, "").strip())
                        || !MemoryEntry.parseTags(tagsField.getText()).equals(editing.tags));
    }

    private void error(String header, Exception e) {
        Alert alert = new Alert(Alert.AlertType.ERROR, e.getMessage(), ButtonType.OK);
        alert.initOwner(root.getScene().getWindow());
        alert.setHeaderText(header);
        alert.showAndWait();
    }
}
