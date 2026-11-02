package net.talaatharb.harness.guardrails;

import java.util.Set;

import net.talaatharb.harness.config.HarnessSettings;

/** A pluggable policy check. Bind new implementations through the Guice multibinder. */
public interface Guardrail {

    String id();

    String description();

    Set<GuardrailStage> stages();

    GuardrailResult check(GuardrailStage stage, String content, HarnessSettings settings);
}
