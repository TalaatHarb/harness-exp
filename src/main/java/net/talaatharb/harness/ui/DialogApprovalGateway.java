package net.talaatharb.harness.ui;

import java.util.concurrent.CompletableFuture;

import com.google.inject.Singleton;

import javafx.application.Platform;
import javafx.scene.control.Alert;
import javafx.scene.control.Alert.AlertType;
import javafx.scene.control.ButtonBar.ButtonData;
import javafx.scene.control.ButtonType;
import javafx.scene.control.TextArea;
import net.talaatharb.harness.approval.ApprovalDecision;
import net.talaatharb.harness.approval.ApprovalGateway;
import net.talaatharb.harness.approval.ApprovalRequest;

/** Human-in-the-loop approval via a modal dialog on the FX thread. */
@Singleton
public class DialogApprovalGateway implements ApprovalGateway {

    @Override
    public CompletableFuture<ApprovalDecision> request(ApprovalRequest request) {
        CompletableFuture<ApprovalDecision> future = new CompletableFuture<>();
        Platform.runLater(() -> {
            ButtonType approve = new ButtonType("Approve", ButtonData.OK_DONE);
            ButtonType always = new ButtonType("Always allow this tool", ButtonData.OTHER);
            ButtonType deny = new ButtonType("Deny", ButtonData.CANCEL_CLOSE);
            Alert alert = new Alert(AlertType.CONFIRMATION, null, approve, always, deny);
            alert.setTitle("Approval required");
            alert.setHeaderText("The agent wants to run '" + request.toolName() + "' (" + request.risk() + ")");
            TextArea args = new TextArea(request.argumentsJson() + "\n\n" + request.reason());
            args.setEditable(false);
            args.setWrapText(true);
            args.setPrefRowCount(6);
            alert.getDialogPane().setContent(args);
            ButtonType chosen = alert.showAndWait().orElse(deny);
            future.complete(chosen == approve ? ApprovalDecision.APPROVED
                    : chosen == always ? ApprovalDecision.APPROVED_ALWAYS : ApprovalDecision.DENIED);
        });
        return future;
    }
}
