package net.talaatharb.harness.ui;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.inject.Singleton;

import javafx.application.Platform;
import javafx.scene.control.Alert;
import javafx.scene.control.Alert.AlertType;
import javafx.scene.control.ButtonBar.ButtonData;
import javafx.scene.control.ButtonType;
import javafx.scene.control.TextArea;
import javafx.stage.Stage;
import javafx.stage.Window;
import net.talaatharb.harness.approval.ApprovalDecision;
import net.talaatharb.harness.approval.ApprovalGateway;
import net.talaatharb.harness.approval.ApprovalRequest;

/** Human-in-the-loop approval via a modal dialog on the FX thread. */
@Singleton
public class DialogApprovalGateway implements ApprovalGateway {
    private static final Logger LOG = LoggerFactory.getLogger(DialogApprovalGateway.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override
    public CompletableFuture<ApprovalDecision> request(ApprovalRequest request) {
        CompletableFuture<ApprovalDecision> future = new CompletableFuture<>();
        Platform.runLater(() -> {
            if (future.isDone()) {
                return;
            }
            try {
                show(request, future);
            } catch (RuntimeException e) {
                LOG.error("Could not show the approval dialog for '{}'", request.toolName(), e);
                future.complete(ApprovalDecision.DENIED);
            }
        });
        return future;
    }

    private static void show(ApprovalRequest request, CompletableFuture<ApprovalDecision> future) {
        ButtonType approve = new ButtonType("Approve", ButtonData.OK_DONE);
        ButtonType always = new ButtonType("Always allow this tool", ButtonData.OTHER);
        ButtonType deny = new ButtonType("Deny", ButtonData.CANCEL_CLOSE);
        Alert alert = request.allowAlways()
                ? new Alert(AlertType.CONFIRMATION, null, approve, always, deny)
                : new Alert(AlertType.CONFIRMATION, null, approve, deny);
        owner().ifPresent(window -> {
            alert.initOwner(window);
            if (window instanceof Stage stage && stage.isIconified()) {
                stage.setIconified(false);
            }
        });
        alert.setTitle("Approval required");
        alert.setHeaderText("The agent wants to run '" + request.toolName() + "' (" + request.risk() + ")");
        TextArea args = new TextArea(readableArguments(request.argumentsJson()) + "\n\n" + request.reason());
        args.setEditable(false);
        args.setWrapText(true);
        args.setPrefRowCount(request.argumentsJson().contains("\\n") ? 20 : 6);
        args.getStyleClass().add("mono");
        alert.getDialogPane().setContent(args);
        alert.setResizable(true);
        // Timeout or Stop cancels the request: close the dialog rather than leave a stale prompt open.
        future.whenComplete((decision, error) -> {
            if (future.isCancelled()) {
                Platform.runLater(() -> {
                    alert.setResult(deny);
                    alert.close();
                });
            }
        });
        alert.setOnShown(e -> ((Stage) alert.getDialogPane().getScene().getWindow()).toFront());
        ButtonType chosen = alert.showAndWait().orElse(deny);
        future.complete(chosen == approve ? ApprovalDecision.APPROVED
                : chosen == always ? ApprovalDecision.APPROVED_ALWAYS : ApprovalDecision.DENIED);
    }

    /** The focused window, else any showing one, so the dialog is centred on and stays above the app. */
    private static Optional<Window> owner() {
        List<Window> showing = Window.getWindows().stream().filter(Window::isShowing).toList();
        return showing.stream().filter(Window::isFocused).findFirst()
                .or(() -> showing.stream().findFirst());
    }

    /** Shows each argument on its own so multi-line values (e.g. create_tool source code) are reviewable. */
    static String readableArguments(String json) {
        try {
            Map<String, Object> map = MAPPER.readValue(json, new TypeReference<LinkedHashMap<String, Object>>() {
            });
            if (map.isEmpty()) {
                return "(no arguments)";
            }
            StringBuilder sb = new StringBuilder();
            for (Map.Entry<String, Object> e : map.entrySet()) {
                Object value = e.getValue();
                sb.append(e.getKey()).append(":\n")
                        .append(value instanceof String ? (String) value : MAPPER.writeValueAsString(value))
                        .append("\n\n");
            }
            return sb.toString().strip();
        } catch (Exception e) {
            return json;
        }
    }
}
