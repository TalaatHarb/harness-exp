package net.talaatharb.harness.approval;

public enum ApprovalDecision {
    APPROVED, APPROVED_ALWAYS, DENIED;

    public boolean approved() {
        return this != DENIED;
    }
}
