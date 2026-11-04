package net.talaatharb.harness.guardrails;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import net.talaatharb.harness.config.HarnessSettings;
import net.talaatharb.harness.llm.Attachment;
import net.talaatharb.harness.llm.ChatMessage;

/** Best-effort detection of known credential formats, not arbitrary secrets or binary attachments. */
public class SecretRedactionGuardrail implements Guardrail {
    public static final String ID = "secret-redaction";
    public static final String REDACTED = "[REDACTED]";
    private static final List<Pattern> PATTERNS = List.of(
            Pattern.compile("\\bsk-[A-Za-z0-9_-]{16,}\\b"),
            Pattern.compile("\\b(?:AKIA|ASIA)[0-9A-Z]{16}\\b"),
            Pattern.compile("\\bgh[pousr]_[A-Za-z0-9]{30,}\\b"),
            Pattern.compile("\\bgithub_pat_[A-Za-z0-9_]{20,}\\b"),
            Pattern.compile("\\bAIza[A-Za-z0-9_-]{20,}\\b"),
            Pattern.compile("\\b(?:gsk_|hf_|npm_)[A-Za-z0-9_-]{20,}\\b"),
            Pattern.compile("\\b(?:glpat-|pypi-)[A-Za-z0-9_-]{20,}\\b"),
            Pattern.compile("\\b(?:sk|rk)_(?:live|test)_[A-Za-z0-9]{16,}\\b"),
            Pattern.compile("\\bSG\\.[A-Za-z0-9_-]{16,}\\.[A-Za-z0-9_-]{20,}\\b"),
            Pattern.compile("\\bxox[baprs]-[A-Za-z0-9-]{10,}\\b"),
            Pattern.compile("\\beyJ[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\b"),
            Pattern.compile("-----BEGIN [A-Z ]*PRIVATE KEY-----[\\s\\S]*?(?:-----END [A-Z ]*PRIVATE KEY-----|\\z)"));
    private static final Pattern BEARER = Pattern.compile("(?i)(\\bBearer\\s+)[A-Za-z0-9._~+/-]+=*");
    private static final Pattern BASIC_AUTH = Pattern.compile(
            "(?i)(\\b(?:Proxy-)?Authorization\\b[\"']?\\s*[:=]\\s*[\"']?Basic\\s+)[A-Za-z0-9+/]+=*");
    private static final Pattern TRUNCATED_TITLE_SECRET = Pattern.compile(
            "\\b(?:sk-|github_pat_|gh[pousr]_|AIza|AKIA|ASIA|xox[baprs]-)[A-Za-z0-9_-]*(?:…|\\.\\.\\.)");
    private static final Pattern LABELED = Pattern.compile(
            "(?i)(\\b(?:api[_-]?key|access[_-]?token|auth[_-]?token|refresh[_-]?token|"
            + "client[_-]?secret|secret[_-]?key|aws_secret_access_key|aws_session_token|password)"
            + "\\b[\"']?\\s*[:=]\\s*[\"']?)"
            + "(?!\\[REDACTED\\])[A-Za-z0-9_./+~=:@-]{8,}");

    /** Unconditional redaction, used for titles even when configurable guardrails are disabled. */
    public static String redact(String content) {
        if (content == null) {
            return null;
        }
        String result = content;
        for (Pattern pattern : PATTERNS) {
            result = pattern.matcher(result).replaceAll(REDACTED);
        }
        result = BEARER.matcher(result).replaceAll("$1" + REDACTED);
        result = BASIC_AUTH.matcher(result).replaceAll("$1" + REDACTED);
        return LABELED.matcher(result).replaceAll("$1" + REDACTED);
    }

    public static boolean enabled(HarnessSettings settings) {
        return settings.guardrails.enabled && !settings.guardrails.disabledGuardrails.contains(ID);
    }

    /** Also removes recognizable credential prefixes cut short by older title generators. */
    public static String redactTitle(String title) {
        String safe = redact(title);
        return safe == null ? null : TRUNCATED_TITLE_SECRET.matcher(safe).replaceAll(REDACTED);
    }

    public static String redact(String content, HarnessSettings settings) {
        return enabled(settings) ? redact(content) : content;
    }

    public static Attachment sanitizeAttachment(Attachment attachment, HarnessSettings settings) {
        return new Attachment(redact(attachment.name(), settings), redact(attachment.mimeType(), settings),
                attachment.kind(), attachment.size(), redact(attachment.text(), settings),
                attachment.data(), attachment.truncated());
    }

    /** Sanitizes every textual field without modifying binary attachment payloads. */
    public static ChatMessage sanitizeMessage(ChatMessage message, HarnessSettings settings) {
        return new ChatMessage(message.role(), redact(message.content(), settings), redact(message.name(), settings),
                message.attachments().stream().map(a -> sanitizeAttachment(a, settings)).toList());
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String description() {
        return "Redacts known API key, token and private key patterns from input, context and output.";
    }

    @Override
    public Set<GuardrailStage> stages() {
        return EnumSet.of(GuardrailStage.INPUT, GuardrailStage.OUTPUT);
    }

    @Override
    public GuardrailResult check(GuardrailStage stage, String content, HarnessSettings settings) {
        if (content == null) {
            return GuardrailResult.allow(null);
        }
        String result = redact(content);
        return result.equals(content) ? GuardrailResult.allow(content)
                : GuardrailResult.modify(result, "Redacted possible secrets");
    }
}
