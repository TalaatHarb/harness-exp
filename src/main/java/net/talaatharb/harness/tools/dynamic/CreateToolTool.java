package net.talaatharb.harness.tools.dynamic;

import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.inject.Inject;
import com.google.inject.Provider;

import net.talaatharb.harness.tools.RiskLevel;
import net.talaatharb.harness.tools.Tool;
import net.talaatharb.harness.tools.ToolArguments;
import net.talaatharb.harness.tools.ToolCallParser;
import net.talaatharb.harness.tools.ToolResult;

/** Lets the agent extend its own tool list by supplying Java source for a {@link DynamicTool}. */
public class CreateToolTool implements Tool {
    public static final String NAME = "create_tool";
    private static final Logger LOG = LoggerFactory.getLogger(CreateToolTool.class);

    private final DynamicToolRegistry registry;
    private final Provider<Set<Tool>> builtins;

    @Inject
    public CreateToolTool(DynamicToolRegistry registry, Provider<Set<Tool>> builtins) {
        this.registry = registry;
        this.builtins = builtins;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "Creates (or replaces) a tool at runtime from Java source; it becomes callable immediately and is "
                + "kept across restarts. The Java source must contain a public class with a public no-arg constructor "
                + "implementing " + DynamicTool.class.getName() + ", i.e. `public String execute("
                + "java.util.Map<String, Object> arguments) throws Exception` returning the result text. "
                + "DynamicTool and java.util.* are imported automatically; only the JDK is available. "
                + "Compilation errors are returned so you can fix and retry.";
    }

    @Override
    public String argumentsHint() {
        return "{\"name\": \"word_count\", \"description\": \"Counts words in a text\", "
                + "\"argumentsHint\": \"{\\\"text\\\": \\\"string\\\"}\"} followed, in the same reply, by the full "
                + "Java source in a ```java block (or pass it as \"sourceCode\")";
    }

    @Override
    public RiskLevel risk() {
        return RiskLevel.SENSITIVE;
    }

    /** Each call compiles and loads different code, so one approval must never cover the next. */
    @Override
    public boolean alwaysRequiresApproval() {
        return true;
    }

    @Override
    public ToolResult execute(Map<String, Object> arguments) {
        DynamicToolSpec spec;
        try {
            spec = new DynamicToolSpec(ToolArguments.string(arguments, "name", ""),
                    ToolArguments.string(arguments, "description", ""),
                    arguments != null && arguments.get("argumentsHint") instanceof Map<?, ?> hint
                            ? ToolCallParser.toJson(hint) : ToolArguments.string(arguments, "argumentsHint", ""),
                    ToolArguments.string(arguments, "sourceCode", null));
        } catch (IllegalArgumentException e) {
            return ToolResult.error("Create tool arguments rejected: " + e.getMessage());
        }
        applyDefaults(spec);
        Set<String> reserved = builtins.get().stream().map(Tool::name).collect(Collectors.toSet());
        try {
            Tool tool = registry.create(spec, reserved);
            return ToolResult.ok("Tool '" + tool.name() + "' is ready. Call it with arguments "
                    + tool.argumentsHint());
        } catch (DynamicToolException e) {
            LOG.warn("create_tool '{}' failed: {}", spec.name, e.getMessage());
            return ToolResult.error(e.getMessage());
        }
    }

    /** Small models often send only the source; derive what can be derived instead of failing. */
    static void applyDefaults(DynamicToolSpec spec) {
        if ((spec.name == null || spec.name.isBlank()) && spec.sourceCode != null) {
            try {
                String className = DynamicToolCompiler.className(spec.sourceCode);
                String simple = className.substring(className.lastIndexOf('.') + 1);
                spec.name = simple.replaceAll("([a-z0-9])([A-Z])", "$1_$2").toLowerCase(Locale.ROOT);
            } catch (DynamicToolException ignored) {
                // reported by validation with a clearer message
            }
        }
        if ((spec.description == null || spec.description.isBlank()) && spec.name != null) {
            spec.description = "Agent-created tool '" + spec.name + "'";
        }
    }

}