package net.talaatharb.harness.approval;

import java.util.concurrent.CompletableFuture;

/** Asks a human to approve a request. The UI implementation shows a dialog. */
public interface ApprovalGateway {

    CompletableFuture<ApprovalDecision> request(ApprovalRequest request);
}
