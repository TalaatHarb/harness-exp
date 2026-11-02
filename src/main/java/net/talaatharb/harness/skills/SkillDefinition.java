package net.talaatharb.harness.skills;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * A reusable block of instructions injected into the context. A skill with no
 * triggers is always active (when enabled); otherwise it is only injected when
 * the latest user message contains one of its trigger phrases.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class SkillDefinition {
    public String name = "";
    public String description = "";
    public String instructions = "";
    public List<String> triggers = new ArrayList<>();
    public boolean enabled = true;

    public SkillDefinition() {
    }

    public SkillDefinition(String name, String description, String instructions, List<String> triggers,
            boolean enabled) {
        this.name = name;
        this.description = description;
        this.instructions = instructions;
        this.triggers = new ArrayList<>(triggers);
        this.enabled = enabled;
    }

    public boolean appliesTo(String userMessage) {
        if (!enabled) {
            return false;
        }
        if (triggers == null || triggers.isEmpty()) {
            return true;
        }
        String text = userMessage == null ? "" : userMessage.toLowerCase(Locale.ROOT);
        return triggers.stream().filter(t -> t != null && !t.isBlank())
                .anyMatch(t -> text.contains(t.toLowerCase(Locale.ROOT).trim()));
    }

    public static List<SkillDefinition> defaults() {
        return List.of(
                new SkillDefinition("Code reviewer", "Reviews code snippets",
                        "When reviewing code, list concrete bugs first, then risks, then style. Quote the relevant lines.",
                        List.of("review", "code"), true),
                new SkillDefinition("Summarizer", "Produces short summaries",
                        "When asked to summarize, answer with at most 5 bullet points.",
                        List.of("summarize", "summary", "tl;dr"), true));
    }

    @Override
    public String toString() {
        return name;
    }
}
