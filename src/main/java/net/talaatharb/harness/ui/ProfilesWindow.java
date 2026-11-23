package net.talaatharb.harness.ui;

import java.io.IOException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javafx.geometry.Insets;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.Window;
import net.talaatharb.harness.config.ProfileManager;

/** Profile changes are staged for the next application launch. */
final class ProfilesWindow {
    private static final Logger LOG = LoggerFactory.getLogger(ProfilesWindow.class);

    private ProfilesWindow() {
    }

    static void show(Window owner, ProfileManager profiles) {
        try {
            Dialog<ButtonType> dialog = dialog(owner, profiles);
            dialog.showAndWait().filter(button -> button.getButtonData() == ButtonBar.ButtonData.OK_DONE)
                    .ifPresent(button -> {
                        Alert notice = new Alert(Alert.AlertType.INFORMATION,
                                "The selected profile will be used after you close and reopen the application.",
                                ButtonType.OK);
                        notice.initOwner(owner);
                        notice.setHeaderText(null);
                        notice.showAndWait();
                    });
        } catch (IOException e) {
            LOG.error("Could not load profiles", e);
            Alert alert = new Alert(Alert.AlertType.ERROR, "Could not load profiles: " + e.getMessage(), ButtonType.OK);
            alert.initOwner(owner);
            alert.setHeaderText(null);
            alert.showAndWait();
        }
    }

    static Dialog<ButtonType> dialog(Window owner, ProfileManager profiles) throws IOException {
        Dialog<ButtonType> dialog = new Dialog<>();
        if (owner != null) {
            dialog.initOwner(owner);
        }
        dialog.setTitle("Harness profiles");
        dialog.setHeaderText("Running profile: " + profiles.running());
        ComboBox<String> choices = new ComboBox<>();
        choices.setId("profileChoice");
        choices.getItems().setAll(profiles.profiles());
        choices.setValue(profiles.selected());
        choices.setMaxWidth(Double.MAX_VALUE);
        Label help = new Label("The selected profile is used after restarting. Each profile has its own settings "
                + "and default storage. Existing explicit storage paths can be shared.");
        help.setWrapText(true);
        Label error = new Label();
        error.setId("profileError");
        error.setWrapText(true);
        TextField name = new TextField();
        name.setId("newProfileName");
        name.setPromptText("New profile name (letters, digits, - or _)");
        HBox.setHgrow(name, javafx.scene.layout.Priority.ALWAYS);
        Button create = new Button("Create");
        create.setId("createProfile");
        create.setOnAction(e -> {
            try {
                String id = profiles.create(name.getText());
                choices.getItems().setAll(profiles.profiles());
                choices.setValue(id);
                name.clear();
                error.setText("");
            } catch (IOException | IllegalArgumentException failure) {
                LOG.warn("Could not create profile", failure);
                error.setText("Could not create profile: " + failure.getMessage());
            }
        });
        VBox content = new VBox(10, help, new Label("Profile for next launch"), choices,
                new HBox(6, name, create), error);
        content.setPadding(new Insets(10));
        dialog.getDialogPane().setContent(content);
        ButtonType apply = new ButtonType("Use after restart", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(apply, ButtonType.CANCEL);
        Button applyButton = (Button) dialog.getDialogPane().lookupButton(apply);
        applyButton.setId("selectProfile");
        applyButton.addEventFilter(javafx.event.ActionEvent.ACTION, e -> {
            try {
                profiles.select(choices.getValue());
            } catch (IOException | IllegalArgumentException failure) {
                LOG.warn("Could not select profile", failure);
                error.setText("Could not select profile: " + failure.getMessage());
                e.consume();
            }
        });
        return dialog;
    }
}
