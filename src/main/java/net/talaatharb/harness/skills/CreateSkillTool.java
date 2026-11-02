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
        SkillDefinition skill = new SkillDefinition(str(arguments, "name"), str(arguments, "description"),
                str(arguments, "instructions"), triggers(arguments == null ? null : arguments.get("triggers")), true);
        boolean replacing = repository.findByName(skill.name).isPresent();
        try {
            SkillDefinition saved = repository.save(skill);
            String when = saved.triggers.isEmpty() ? "It is always active."
                    : "It is active when the user's message contains: " + String.join(", ", saved.triggers) + ".";
            return ToolResult.ok("Skill '" + saved.name + "' " + (replacing ? "updated" : "created") + " ("
                    + repository.file(saved.id) + "). " + when);
        } catch (IllegalArgumentException e) {
            LOG.warn("create_skill '{}' rejected: {}", skill.name, e.getMessage());
            return ToolResult.error(e.getMessage());
        } catch (IOException e) {
            LOG.error("create_skill '{}' could not be written", skill.name, e);
            return ToolResult.error("Could not save the skill: " + e.getMessage());
        }
    }

    /** Accepts a JSON list or a comma separated string. */
    static List<String> triggers(Object value) {
        if (value == null) {
            return List.of();
        }
        List<String> items = new ArrayList<>();
        if (value instanceof Collection<?> list) {
            list.forEach(item -> items.add(item == null ? null : String.valueOf(item)));
            return SkillFiles.clean(items);
        }
        return SkillFiles.parseList(String.valueOf(value));
    }

    private static String str(Map<String, Object> arguments, String key) {
        Object value = arguments == null ? null : arguments.get(key);
        return value == null ? "" : String.valueOf(value);
    }
}
