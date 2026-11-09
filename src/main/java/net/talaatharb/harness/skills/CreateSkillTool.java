package net.talaatharb.harness.skills;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.inject.Inject;

import net.talaatharb.harness.tools.RiskLevel;
import net.talaatharb.harness.tools.Tool;
import net.talaatharb.harness.tools.ToolArguments;
import net.talaatharb.harness.tools.ToolResult;

/** Lets the agent write (or update) a reusable skill in the skills folder. */
public class CreateSkillTool implements Tool {
    public static final String NAME = "create_skill";
    private static final Logger LOG = LoggerFactory.getLogger(CreateSkillTool.class);

    private final SkillRepository repository;

    @Inject
    public CreateSkillTool(SkillRepository repository) {
        this.repository = repository;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "Creates or updates a reusable skill: named instructions added to your system prompt whenever the "
                + "user's message contains one of its trigger phrases (no triggers = always added). It is saved to "
                + "the skills folder and kept across restarts; a skill with the same name is replaced. Use it for "
                + "durable guidance worth reusing, not for one-off facts.";
    }

    @Override
    public String argumentsHint() {
        return "{\"name\": \"SQL reviewer\", \"description\": \"Reviews SQL queries\", "
                + "\"triggers\": [\"sql\", \"query\"], \"instructions\": \"When reviewing SQL, ...\"}";
    }

    @Override
    public RiskLevel risk() {
        return RiskLevel.SENSITIVE;
    }

    /** A skill permanently rewrites the agent's instructions, so each one must be reviewed by a human. */
    @Override
    public boolean alwaysRequiresApproval() {
        return true;
    }

    @Override
    public ToolResult execute(Map<String, Object> arguments) {
        try {
            SkillDefinition skill = new SkillDefinition(ToolArguments.string(arguments, "name", null),
                    ToolArguments.string(arguments, "description", ""),
                    ToolArguments.string(arguments, "instructions", null),
                    triggers(arguments == null ? null : arguments.get("triggers")), true);
            boolean replacing = repository.findByName(skill.name).isPresent();
            SkillDefinition saved = repository.save(skill);
            String when = saved.triggers.isEmpty() ? "It is always active."
                    : "It is active when the user's message contains: " + String.join(", ", saved.triggers) + ".";
            return ToolResult.ok("Skill '" + saved.name + "' " + (replacing ? "updated" : "created") + " ("
                    + repository.file(saved.id) + "). " + when);
        } catch (IllegalArgumentException e) {
            LOG.warn("create_skill rejected: {}", e.getMessage());
            return ToolResult.error(e.getMessage());
        } catch (IOException e) {
            LOG.error("create_skill could not be written", e);
            return ToolResult.error("Could not save the skill: " + e.getMessage());
        }
    }

    /** Accepts a JSON list or a comma separated string. */
    static List<String> triggers(Object value) {
        if (ToolArguments.omitted(value)) {
            return List.of();
        }
        List<String> items = new ArrayList<>();
        if (value instanceof Collection<?> list) {
            for (Object item : list) {
                if (item != null && !(item instanceof String)) {
                    throw new IllegalArgumentException("triggers must contain strings");
                }
                items.add((String) item);
            }
            return SkillFiles.clean(items);
        }
        if (value instanceof String text) {
            return SkillFiles.parseList(text);
        }
        throw new IllegalArgumentException("triggers must be a string or an array of strings");
    }
}
