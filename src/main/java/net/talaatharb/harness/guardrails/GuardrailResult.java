package net.talaatharb.harness.guardrails;

/**
 * Outcome of a guardrail check. {@link Decision#MODIFY} lets a guardrail
 * rewrite content (e.g. redaction) while still allowing it through.
 */
public record GuardrailResult(Decision decision, String content, String reason) {

    public enum Decision {
        ALLOW, MODIFY, BLOCK
    }

    public static GuardrailResult allow(String content) {
        return new GuardrailResult(Decision.ALLOW, content, null);
    }

    public static GuardrailResult modify(String content, String reason) {
        return new GuardrailResult(Decision.MODIFY, content, reason);
    }

    public static GuardrailResult block(String reason) {
        return new GuardrailResult(Decision.BLOCK, null, reason);
    }

    public boolean blocked() {
        return decision == Decision.BLOCK;
    }
}
