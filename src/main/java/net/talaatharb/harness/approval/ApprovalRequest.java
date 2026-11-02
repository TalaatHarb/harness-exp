package net.talaatharb.harness.approval;

import net.talaatharb.harness.tools.RiskLevel;

public record ApprovalRequest(String toolName, String argumentsJson, RiskLevel risk, String reason) {
}
