package net.talaatharb.harness.ui;

import java.io.IOException;
import java.io.UncheckedIOException;

import com.google.inject.Inject;
import com.google.inject.Injector;
import com.google.inject.Singleton;

import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.stage.Stage;
import javafx.stage.Window;

/** Opens the harness configuration window (one instance; recreated each time so it reflects current settings). */
@Singleton
public class SettingsWindow {
    private final Injector injector;
    private Stage stage;

    @Inject
    public SettingsWindow(Injector injector) {
        this.injector = injector;
    }

    public void show(Window owner) {
        if (stage != null && stage.isShowing()) {
            stage.toFront();
            return;
        }
        try {
            FXMLLoader loader = new FXMLLoader(SettingsWindow.class.getResource("settings.fxml"));
            loader.setControllerFactory(injector::getInstance);
            Parent root = loader.load();
            Scene scene = new Scene(root, 900, 640);
            UiSupport.applyStyles(scene);
            stage = new Stage();
            stage.initOwner(owner);
            stage.setTitle("Harness configuration");
            stage.setScene(scene);
            stage.show();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
