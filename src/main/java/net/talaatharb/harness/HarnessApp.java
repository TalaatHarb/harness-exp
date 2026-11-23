package net.talaatharb.harness;

import java.io.IOException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.inject.Guice;
import com.google.inject.Injector;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;
import javafx.stage.Stage;
import net.talaatharb.harness.config.AppModule;
import net.talaatharb.harness.config.ProfileManager;
import net.talaatharb.harness.history.ConversationStore;
import net.talaatharb.harness.memory.MemoryService;
import net.talaatharb.harness.rag.RagService;
import net.talaatharb.harness.tools.mcp.McpClientManager;
import net.talaatharb.harness.ui.MainController;
import net.talaatharb.harness.ui.UiSupport;

public class HarnessApp extends Application {
    private static final Logger LOG = LoggerFactory.getLogger(HarnessApp.class);
    private Injector injector;
    private MainController mainController;
    private Exception startupError;

    @Override
    public void init() {
        Thread.setDefaultUncaughtExceptionHandler(
                (thread, e) -> LOG.error("Uncaught exception on thread {}", thread.getName(), e));
        try {
            ProfileManager profiles = new ProfileManager(AppModule.defaultSettingsPath());
            profiles.initialize();
            injector = Guice.createInjector(new AppModule(profiles.runningSettingsPath(), profiles));
        } catch (IOException | RuntimeException e) {
            LOG.error("Could not initialize the selected harness profile", e);
            startupError = e;
        }
    }

    @Override
    public void start(Stage stage) throws IOException {
        if (startupError != null) {
            Alert alert = new Alert(Alert.AlertType.ERROR,
                    "Could not open the selected profile: " + startupError.getMessage(), ButtonType.OK);
            alert.setHeaderText("Harness could not start");
            alert.showAndWait();
            Platform.exit();
            return;
        }
        UiSupport.setHostServices(getHostServices());
        FXMLLoader loader = new FXMLLoader(getClass().getResource("ui/main.fxml"));
        loader.setControllerFactory(injector::getInstance);
        Parent root = loader.load();
        mainController = loader.getController();
        Scene scene = new Scene(root, 980, 720);
        UiSupport.applyStyles(scene);
        stage.setTitle("Harness Experiment");
        stage.setScene(scene);
        stage.show();
    }

    @Override
    public void stop() {
        if (mainController != null) {
            mainController.shutdown();
        }
        if (injector != null) {
            injector.getInstance(McpClientManager.class).close();
            injector.getInstance(MemoryService.class).close();
            injector.getInstance(RagService.class).close();
            injector.getInstance(ConversationStore.class).close();
        }
    }

    public static void main(String[] args) {
        launch(args);
    }
}
