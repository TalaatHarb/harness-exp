package net.talaatharb.harness.approval;

import java.util.concurrent.CompletableFuture;
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

    /**
     * Tools that must be approved on every call, whatever the policy or the "always allow" list: dangerous tools and
     * tools that declare {@link Tool#alwaysRequiresApproval()} (e.g. agent-written code).
     */
    public boolean isMandatory(Tool tool) {
        return tool.alwaysRequiresApproval() || tool.risk() == RiskLevel.DANGEROUS;
    }

    public boolean requiresApproval(Tool tool, HarnessSettings settings) {
        if (isMandatory(tool)) {
            return true;
        }
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

    public ApprovalRequest requestFor(Tool tool, String argumentsJson, HarnessSettings settings) {
        boolean mandatory = isMandatory(tool);
        String reason = mandatory
                ? "This tool needs approval on every call (" + (tool.alwaysRequiresApproval()
                        ? "the tool requires per-call review" : "it is dangerous") + ")."
                : "Policy: " + settings.approval.policy;
        return new ApprovalRequest(tool.name(), argumentsJson, tool.risk(), reason, !mandatory);
    }

    public ApprovalDecision await(ApprovalRequest request, HarnessSettings settings) throws InterruptedException {
        CompletableFuture<ApprovalDecision> pending;
        try {
            pending = gateway.request(request);
        } catch (RuntimeException e) {
            LOG.error("Approval gateway failed for tool '{}'; denying", request.toolName(), e);
            return ApprovalDecision.DENIED;
        }
        try {
            ApprovalDecision decision = pending.get(Math.max(1, settings.approval.timeoutSeconds), TimeUnit.SECONDS);
            if (decision == ApprovalDecision.APPROVED_ALWAYS) {
                if (!request.allowAlways()) {
                    LOG.warn("Ignoring 'always allow' for '{}': it needs approval on every call", request.toolName());
                    return ApprovalDecision.APPROVED;
                }
                HarnessSettings updated = store.copy();
                updated.approval.alwaysAllowedTools.add(request.toolName());
                store.replace(updated);
            }
            return decision == null ? ApprovalDecision.DENIED : decision;
        } catch (TimeoutException e) {
            LOG.warn("Approval for tool '{}' timed out after {}s; denying", request.toolName(),
                    settings.approval.timeoutSeconds);
            pending.cancel(true);
            return ApprovalDecision.DENIED;
        } catch (InterruptedException e) {
            pending.cancel(true);
            throw e;
        } catch (ExecutionException | java.util.concurrent.CancellationException e) {
            LOG.error("Approval gateway failed for tool '{}'; denying", request.toolName(), e);
            return ApprovalDecision.DENIED;
        }
    }
}
