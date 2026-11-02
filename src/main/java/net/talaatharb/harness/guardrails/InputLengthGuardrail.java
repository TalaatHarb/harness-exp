package net.talaatharb.harness.guardrails;

import java.util.EnumSet;
import java.util.Set;

import net.talaatharb.harness.config.HarnessSettings;

public class InputLengthGuardrail implements Guardrail {

    @Override
    public String id() {
        return "input-length";
    }

    @Override
    public String description() {
        return "Blocks user messages longer than the configured character limit.";
    }

    @Override
    public Set<GuardrailStage> stages() {
        return EnumSet.of(GuardrailStage.INPUT);
    }

    @Override
    public GuardrailResult check(GuardrailStage stage, String content, HarnessSettings settings) {
        int max = settings.guardrails.maxInputChars;
        if (max > 0 && content != null && content.length() > max) {
            return GuardrailResult.block("Message is " + content.length() + " characters; limit is " + max);
        }
        return GuardrailResult.allow(content);
    }
}
