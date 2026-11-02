package net.talaatharb.harness.tools;

/** Used by the approval policy to decide when a human must confirm a tool call. */
public enum RiskLevel {
    SAFE, SENSITIVE, DANGEROUS
}
