package net.talaatharb.harness;

import java.io.IOException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.inject.Guice;
import com.google.inject.Injector;

import javafx.application.Application;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.stage.Stage;
import net.talaatharb.harness.config.AppModule;
import net.talaatharb.harness.ui.MainController;
import net.talaatharb.harness.ui.UiSupport;

public class HarnessApp extends Application {
    private static final Logger LOG = LoggerFactory.getLogger(HarnessApp.class);
    private Injector injector;
    private MainController mainController;

    @Override
    public void init() {
        Thread.setDefaultUncaughtExceptionHandler(
                (thread, e) -> LOG.error("Uncaught exception on thread {}", thread.getName(), e));
        injector = Guice.createInjector(new AppModule());
    }

    @Override
    public void start(Stage stage) throws IOException {
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
    }

    public static void main(String[] args) {
        launch(args);
    }
}
