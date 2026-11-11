package net.talaatharb.harness.ui.sections;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.control.ComboBox;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;
import net.talaatharb.harness.config.HarnessSettings;
import net.talaatharb.harness.rag.RagSettings;
import net.talaatharb.harness.rag.RagSettings.EmbedderType;

class RagSectionControllerTest {
    @BeforeAll
    static void startToolkit() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(
                !System.getProperty("os.name").toLowerCase(java.util.Locale.ROOT).contains("linux")
                        || System.getenv("DISPLAY") != null,
                "Run JavaFX smoke tests under xvfb-run on headless Linux");
        CountDownLatch ready = new CountDownLatch(1);
        try {
            Platform.startup(() -> {
                Platform.setImplicitExit(false);
                ready.countDown();
            });
        } catch (IllegalStateException alreadyStarted) {
            ready.countDown();
        } catch (UnsupportedOperationException unavailable) {
            org.junit.jupiter.api.Assumptions.assumeTrue(false, "JavaFX display unavailable: " + unavailable);
        }
        assertTrue(ready.await(15, TimeUnit.SECONDS));
    }

    @Test
    void cloudConfigurationRoundTripsAndProviderDefaultsFollowSelection() throws Exception {
        FutureTask<Void> task = new FutureTask<>(() -> {
            FXMLLoader loader = new FXMLLoader(getClass().getResource("rag-section.fxml"));
            Parent root = loader.load();
            RagSectionController controller = loader.getController();
            HarnessSettings settings = new HarnessSettings();
            controller.load(settings);
            @SuppressWarnings("unchecked")
            ComboBox<EmbedderType> provider = (ComboBox<EmbedderType>) root.lookup("#embedderCombo");
            TextField model = (TextField) root.lookup("#modelField");
            TextField url = (TextField) root.lookup("#baseUrlField");
            PasswordField key = (PasswordField) root.lookup("#apiKeyField");
            provider.setValue(EmbedderType.OPENAI);
            assertEquals(RagSettings.DEFAULT_OPENAI_MODEL, model.getText());
            assertTrue(url.getPromptText().contains("api.openai.com"));
            url.setText("https://embeddings.example/v1");
            key.setText("embedding-key");
            model.setText("custom-embedding");
            controller.apply(settings);
            assertEquals(EmbedderType.OPENAI, settings.rag.embedder);
            assertEquals("embedding-key", settings.rag.embeddingApiKey);
            assertEquals("https://embeddings.example/v1", settings.rag.embeddingBaseUrl);
            assertEquals("custom-embedding", settings.rag.embeddingModel);
            controller.load(settings);
            assertEquals("custom-embedding", model.getText());
            assertEquals("embedding-key", key.getText());
            provider.setValue(EmbedderType.BUILT_IN);
            assertTrue(key.isDisabled());
            assertTrue(url.isDisabled());
            provider.setValue(EmbedderType.OPENAI);
            assertFalse(key.isDisabled());
            return null;
        });
        Platform.runLater(task);
        task.get(20, TimeUnit.SECONDS);
    }
}
