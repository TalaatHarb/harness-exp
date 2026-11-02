package net.talaatharb.harness.approval;

import java.util.HashSet;
import java.util.Set;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public class ApprovalSettings {
    public ApprovalPolicy policy = ApprovalPolicy.RISKY_TOOLS;
    /** Tools the user approved with "always allow"; they skip the prompt. */
    public Set<String> alwaysAllowedTools = new HashSet<>();
    /** Pending approvals are denied after this many seconds. */
    public int timeoutSeconds = 300;
}
