package net.talaatharb.harness.approval;

/** When the agent must pause for a human before running a tool. */
public enum ApprovalPolicy {
    NEVER("Never ask (fully autonomous)"),
    RISKY_TOOLS("Ask for sensitive / dangerous tools"),
    ALL_TOOLS("Ask before every tool call");

    private final String label;

    ApprovalPolicy(String label) {
        this.label = label;
    }

    @Override
    public String toString() {
        return label;
    }
}
