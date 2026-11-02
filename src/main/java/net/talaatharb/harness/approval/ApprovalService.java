package net.talaatharb.harness.approval;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.inject.Inject;
import com.google.inject.Singleton;

import net.talaatharb.harness.config.HarnessSettings;
import net.talaatharb.harness.config.SettingsStore;
import net.talaatharb.harness.tools.RiskLevel;
import net.talaatharb.harness.tools.Tool;

/** Applies the approval policy and blocks the agent thread until a human decides. */
@Singleton
public class ApprovalService {
    private static final Logger LOG = LoggerFactory.getLogger(ApprovalService.class);

    private final ApprovalGateway gateway;
    private final SettingsStore store;

    @Inject
    public ApprovalService(ApprovalGateway gateway, SettingsStore store) {
        this.gateway = gateway;
        this.store = store;
    }

    public boolean requiresApproval(Tool tool, HarnessSettings settings) {
        ApprovalSettings approval = settings.approval;
        if (approval.alwaysAllowedTools.contains(tool.name())) {
            return false;
        }
        switch (approval.policy) {
            case ALL_TOOLS:
                return true;
            case RISKY_TOOLS:
                return tool.risk() != RiskLevel.SAFE;
            case NEVER:
            default:
                return false;
        }
    }

    public ApprovalDecision await(ApprovalRequest request, HarnessSettings settings) throws InterruptedException {
        try {
            ApprovalDecision decision = gateway.request(request)
                    .get(Math.max(1, settings.approval.timeoutSeconds), TimeUnit.SECONDS);
            if (decision == ApprovalDecision.APPROVED_ALWAYS) {
                HarnessSettings updated = store.copy();
                updated.approval.alwaysAllowedTools.add(request.toolName());
                store.replace(updated);
            }
            return decision == null ? ApprovalDecision.DENIED : decision;
        } catch (TimeoutException e) {
            LOG.warn("Approval for tool '{}' timed out after {}s; denying", request.toolName(),
                    settings.approval.timeoutSeconds);
            return ApprovalDecision.DENIED;
        } catch (ExecutionException e) {
            LOG.error("Approval gateway failed for tool '{}'; denying", request.toolName(), e.getCause());
            return ApprovalDecision.DENIED;
        }
    }
}
