package net.talaatharb.harness.ui.sections;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import net.talaatharb.harness.config.HarnessSettings;
import net.talaatharb.harness.llm.AnthropicModelProvider;
import net.talaatharb.harness.llm.ModelProviderRegistry;
import net.talaatharb.harness.llm.OllamaModelProvider;
import net.talaatharb.harness.llm.OpenAiModelProvider;

class ModelSectionControllerTest {
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
    void fxmlLoadsOptionalSettingsRoundTripAndProviderHints() throws Exception {
        FutureTask<Void> task = new FutureTask<>(() -> {
            ModelProviderRegistry registry = new ModelProviderRegistry(Set.of(new OllamaModelProvider(),
                    new OpenAiModelProvider(), new AnthropicModelProvider()));
            FXMLLoader loader = new FXMLLoader(getClass().getResource("model-section.fxml"));
            loader.setControllerFactory(type -> new ModelSectionController(registry));
            Parent root = loader.load();
            ModelSectionController controller = loader.getController();
            HarnessSettings settings = new HarnessSettings();
            controller.load(settings);
            TextField temperature = (TextField) root.lookup("#temperatureField");
            TextField seed = (TextField) root.lookup("#seedField");
            assertEquals("", temperature.getText());
            assertEquals("", seed.getText());
            controller.apply(settings);
            assertNull(settings.model.temperature);
            assertNull(settings.model.seed);

            temperature.setText("0");
            seed.setText("42");
            controller.apply(settings);
            assertEquals(0.0, settings.model.temperature);
            assertEquals(42L, settings.model.seed);
            controller.load(settings);
            assertEquals("0.0", temperature.getText());
            assertEquals("42", seed.getText());

            @SuppressWarnings("unchecked")
            ComboBox<String> provider = (ComboBox<String>) root.lookup("#providerCombo");
            provider.setValue("anthropic");
            assertTrue(((Label) root.lookup("#samplingLabel")).getText().contains("does not support seed"));
            assertThrows(IllegalArgumentException.class, () -> controller.apply(settings));
            seed.clear();
            temperature.setText("1.1");
            assertThrows(IllegalArgumentException.class, () -> controller.apply(settings));
            temperature.setText("0.5");
            controller.apply(settings);
            assertEquals(0.5, settings.model.temperature);
            assertNull(settings.model.seed);

            provider.setValue("openai");
            temperature.clear();
            seed.setText(Long.toString(Long.MAX_VALUE));
            controller.apply(settings);
            assertNull(settings.model.temperature);
            assertEquals(Long.MAX_VALUE, settings.model.seed);
            provider.setValue("ollama");
            assertThrows(IllegalArgumentException.class, () -> controller.apply(settings));
            seed.clear();
            controller.apply(settings);
            assertNull(settings.model.seed);
            return null;
        });
        Platform.runLater(task);
        task.get(20, TimeUnit.SECONDS);
    }
}
