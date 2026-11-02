package net.talaatharb.harness.approval;

import net.talaatharb.harness.tools.RiskLevel;

/**
 * @param allowAlways whether the user may choose "always allow" (false for tools that must be approved every call)
 */
public record ApprovalRequest(String toolName, String argumentsJson, RiskLevel risk, String reason,
        boolean allowAlways) {

    public ApprovalRequest(String toolName, String argumentsJson, RiskLevel risk, String reason) {
        this(toolName, argumentsJson, risk, reason, true);
    }
}
