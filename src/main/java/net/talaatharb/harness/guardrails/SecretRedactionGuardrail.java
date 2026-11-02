package net.talaatharb.harness.guardrails;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import net.talaatharb.harness.config.HarnessSettings;

/** Redacts common credential formats from model output. */
public class SecretRedactionGuardrail implements Guardrail {
    static final String REDACTED = "[REDACTED]";
    private static final List<Pattern> PATTERNS = List.of(
            Pattern.compile("\\bsk-[A-Za-z0-9_-]{16,}\\b"),
            Pattern.compile("\\bAKIA[0-9A-Z]{16}\\b"),
            Pattern.compile("\\bgh[pousr]_[A-Za-z0-9]{30,}\\b"),
            Pattern.compile("-----BEGIN [A-Z ]*PRIVATE KEY-----[\\s\\S]*?-----END [A-Z ]*PRIVATE KEY-----"));

    @Override
    public String id() {
        return "secret-redaction";
    }

    @Override
    public String description() {
        return "Redacts API keys, tokens and private keys from model output.";
    }

    @Override
    public Set<GuardrailStage> stages() {
        return EnumSet.of(GuardrailStage.OUTPUT);
    }

    @Override
    public GuardrailResult check(GuardrailStage stage, String content, HarnessSettings settings) {
        if (content == null) {
            return GuardrailResult.allow(null);
        }
        String result = content;
        for (Pattern p : PATTERNS) {
            result = p.matcher(result).replaceAll(REDACTED);
        }
        return result.equals(content) ? GuardrailResult.allow(content)
                : GuardrailResult.modify(result, "Redacted possible secrets");
    }
}
