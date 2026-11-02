package net.talaatharb.harness.guardrails;

import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;

import net.talaatharb.harness.config.HarnessSettings;

public class BlockedTermsGuardrail implements Guardrail {

    @Override
    public String id() {
        return "blocked-terms";
    }

    @Override
    public String description() {
        return "Blocks input, tool calls and output containing any configured term.";
    }

    @Override
    public Set<GuardrailStage> stages() {
        return EnumSet.allOf(GuardrailStage.class);
    }

    @Override
    public GuardrailResult check(GuardrailStage stage, String content, HarnessSettings settings) {
        String text = content == null ? "" : content.toLowerCase(Locale.ROOT);
        for (String term : settings.guardrails.blockedTerms) {
            if (term != null && !term.isBlank() && text.contains(term.toLowerCase(Locale.ROOT).trim())) {
                return GuardrailResult.block("Contains blocked term '" + term.trim() + "'");
            }
        }
        return GuardrailResult.allow(content);
    }
}
