package net.talaatharb.harness.skills;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * A reusable block of instructions injected into the context. A skill with no
 * triggers is always active (when enabled); otherwise it is only injected when
 * the latest user message contains one of its trigger phrases.
 * <p>
 * Skills are stored as {@code <skills folder>/<id>/SKILL.md}; see {@link SkillFiles} and {@link SkillRepository}.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class SkillDefinition {
    /** Folder name inside the skills folder; {@code null} for a skill that hasn't been saved yet. */
    public String id;
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

    public SkillDefinition copy() {
        SkillDefinition copy = new SkillDefinition(name, description, instructions,
                triggers == null ? List.of() : triggers, enabled);
        copy.id = id;
        return copy;
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

    /** The skills bundled with the app, seeded into an empty skills folder on first start. */
    public static List<SkillDefinition> defaults() {
        return SkillFiles.bundledDefaults();
    }

    @Override
    public String toString() {
        return name;
    }
}
