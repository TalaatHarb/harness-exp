package net.talaatharb.harness.guardrails;

import java.util.Comparator;
import java.util.List;
import java.util.Set;

import com.google.inject.Inject;
import com.google.inject.Singleton;

import net.talaatharb.harness.config.HarnessSettings;

/** Runs all enabled guardrails for a stage; the first BLOCK wins, MODIFY results are chained. */
@Singleton
public class GuardrailService {
    private final List<Guardrail> guardrails;

    @Inject
    public GuardrailService(Set<Guardrail> guardrails) {
        this.guardrails = guardrails.stream().sorted(Comparator.comparing(Guardrail::id)).toList();
    }

    public List<Guardrail> all() {
        return guardrails;
    }

    public GuardrailResult evaluate(GuardrailStage stage, String content, HarnessSettings settings) {
        if (!settings.guardrails.enabled) {
            return GuardrailResult.allow(content);
        }
        String current = content;
        StringBuilder reasons = new StringBuilder();
        for (Guardrail g : guardrails) {
            if (!g.stages().contains(stage) || settings.guardrails.disabledGuardrails.contains(g.id())) {
                continue;
            }
            GuardrailResult r = g.check(stage, current, settings);
            if (r.blocked()) {
                return GuardrailResult.block("[" + g.id() + "] " + r.reason());
            }
            if (r.decision() == GuardrailResult.Decision.MODIFY) {
                current = r.content();
                reasons.append(reasons.length() > 0 ? "; " : "").append("[").append(g.id()).append("] ")
                        .append(r.reason());
            }
        }
        return reasons.length() == 0 ? GuardrailResult.allow(current)
                : GuardrailResult.modify(current, reasons.toString());
    }
}
