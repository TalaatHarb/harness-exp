package net.talaatharb.harness.guardrails;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import net.talaatharb.harness.config.HarnessSettings;
import net.talaatharb.harness.llm.Attachment;
import net.talaatharb.harness.llm.ChatMessage;

class SecretRedactionGuardrailTest {
    @Test
    void basicAuthRequiresAnAuthorizationHeaderRatherThanOrdinaryProse() {
        assertEquals("Basic conversation", SecretRedactionGuardrail.redact("Basic conversation"));
        assertEquals("A Basic introduction", SecretRedactionGuardrail.redact("A Basic introduction"));
        String credential = java.util.Base64.getEncoder().encodeToString("user:password".getBytes(
                java.nio.charset.StandardCharsets.UTF_8));
        assertEquals("Authorization: Basic [REDACTED]",
                SecretRedactionGuardrail.redact("Authorization: Basic " + credential));
        assertEquals("proxy-authorization: basic [REDACTED]",
                SecretRedactionGuardrail.redact("proxy-authorization: basic " + credential));
        assertEquals("{\"Authorization\":\"Basic [REDACTED]\"}",
                SecretRedactionGuardrail.redact("{\"Authorization\":\"Basic " + credential + "\"}"));
        assertEquals("Bearer [REDACTED]", SecretRedactionGuardrail.redact("Bearer opaque-token"));
    }

    @Test
    void redactsKnownCredentialFamiliesAndLabeledValues() {
        List<String> secrets = List.of(
                "sk-proj-" + "a".repeat(80), "AKIA" + "A".repeat(16), "ASIA" + "B".repeat(16),
                "ghp_" + "a".repeat(36), "github_pat_" + "b".repeat(80),
                "AIza" + "c".repeat(35), "xoxb-" + "1".repeat(24),
                "gsk_" + "a".repeat(32), "hf_" + "a".repeat(32), "npm_" + "a".repeat(32),
                "glpat-" + "a".repeat(32), "pypi-" + "a".repeat(32), "sk_live_" + "a".repeat(24),
                "SG." + "a".repeat(20) + "." + "b".repeat(40),
                "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxIn0.signature",
                "-----BEGIN PRIVATE KEY-----\nabc\n-----END PRIVATE KEY-----",
                "-----BEGIN RSA PRIVATE KEY-----\ntruncated key material");
        for (String secret : secrets) {
            assertEquals("[REDACTED]", SecretRedactionGuardrail.redact(secret), secret);
        }
        assertEquals("Authorization: Bearer [REDACTED]",
                SecretRedactionGuardrail.redact("Authorization: Bearer opaque-token"));
        assertEquals("{\"api_key\":\"[REDACTED]\"}",
                SecretRedactionGuardrail.redact("{\"api_key\":\"opaque-credential-value\"}"));
        assertNull(SecretRedactionGuardrail.redact(null));
        assertEquals("ordinary text ${API_KEY}", SecretRedactionGuardrail.redact("ordinary text ${API_KEY}"));
    }

    @Test
    void inputAndOutputHonorBothDisableSwitches() {
        String secret = "sk-" + "a".repeat(24);
        HarnessSettings settings = new HarnessSettings();
        GuardrailService service = new GuardrailService(Set.of(new SecretRedactionGuardrail()));
        for (GuardrailStage stage : List.of(GuardrailStage.INPUT, GuardrailStage.OUTPUT)) {
            assertEquals("[REDACTED]", service.evaluate(stage, secret, settings).content());
        }
        settings.guardrails.disabledGuardrails.add(SecretRedactionGuardrail.ID);
        assertEquals(secret, SecretRedactionGuardrail.redact(secret, settings));
        assertEquals(secret, service.evaluate(GuardrailStage.INPUT, secret, settings).content());
        settings.guardrails.disabledGuardrails.clear();
        settings.guardrails.enabled = false;
        assertEquals(secret, SecretRedactionGuardrail.redact(secret, settings));
        assertEquals("[REDACTED]", SecretRedactionGuardrail.redact(secret));
    }

    @Test
    void sanitizesAttachmentTextAndMetadataButDoesNotClaimBinaryDetection() {
        String secret = "ghp_" + "a".repeat(36);
        Attachment attachment = new Attachment(secret, "text/" + secret, Attachment.Kind.TEXT,
                40, secret, "YmluYXJ5", false);
        ChatMessage safe = SecretRedactionGuardrail.sanitizeMessage(ChatMessage.user(secret, List.of(attachment)),
                new HarnessSettings());
        assertFalse(safe.contentForModel().contains(secret));
        assertEquals("YmluYXJ5", safe.attachments().get(0).data());
        assertEquals("[REDACTED]", safe.attachments().get(0).name());
    }
}
