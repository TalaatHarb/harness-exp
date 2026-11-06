package net.talaatharb.harness.ui;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.inject.Singleton;

import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;
import net.talaatharb.harness.interaction.DecisionRequest;
import net.talaatharb.harness.interaction.DecisionResponse;
import net.talaatharb.harness.interaction.UserPrompter;

/** Shows the decision window on the FX thread. Closing it without answering completes the future with null. */
@Singleton
public class DialogUserPrompter implements UserPrompter {
    private static final Logger LOG = LoggerFactory.getLogger(DialogUserPrompter.class);

    @Override
    public CompletableFuture<DecisionResponse> ask(DecisionRequest request) {
        CompletableFuture<DecisionResponse> future = new CompletableFuture<>();
        Platform.runLater(() -> {
            if (future.isDone()) {
                return;
            }
            try {
                show(request, future);
            } catch (RuntimeException e) {
                LOG.error("Could not show the decision window", e);
                future.completeExceptionally(e);
            }
        });
        return future;
    }

    private static void show(DecisionRequest request, CompletableFuture<DecisionResponse> future) {
        Stage stage = new Stage();
        stage.setTitle("The agent needs your input");
        owner().ifPresent(window -> {
            stage.initOwner(window);
            if (window instanceof Stage s && s.isIconified()) {
                s.setIconified(false);
            }
        });
        stage.initModality(Modality.APPLICATION_MODAL);
        stage.setAlwaysOnTop(true);
        stage.setOnHidden(e -> future.complete(null));
        Scene scene = new Scene(DecisionView.build(request, answer -> {
            future.complete(answer);
            stage.close();
        }));
        UiSupport.applyStyles(scene);
        stage.setScene(scene);
        // Timeout or Stop cancels the request: close the window rather than leave a stale prompt open.
        future.whenComplete((answer, error) -> {
            if (future.isCancelled()) {
                Platform.runLater(stage::close);
            }
        });
        stage.show();
        stage.toFront();
    }

    private static Optional<Window> owner() {
        List<Window> showing = Window.getWindows().stream().filter(Window::isShowing).toList();
        return showing.stream().filter(Window::isFocused).findFirst().or(() -> showing.stream().findFirst());
    }
}
