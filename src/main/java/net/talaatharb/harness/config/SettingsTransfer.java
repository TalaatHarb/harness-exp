package net.talaatharb.harness.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import net.talaatharb.harness.tools.mcp.McpServerConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.inject.Inject;
import com.google.inject.Provider;
import com.google.inject.Singleton;

import net.talaatharb.harness.memory.MemoryEntry;
import net.talaatharb.harness.memory.MemoryService;
import net.talaatharb.harness.memory.MemorySettings;
import net.talaatharb.harness.memory.MemoryStore;
import net.talaatharb.harness.skills.SkillDefinition;
import net.talaatharb.harness.skills.SkillRepository;
import net.talaatharb.harness.tools.Tool;
import net.talaatharb.harness.tools.dynamic.DynamicToolException;
import net.talaatharb.harness.tools.dynamic.DynamicToolRegistry;
import net.talaatharb.harness.tools.dynamic.DynamicToolSpec;

/** Exports/imports the whole harness setup (settings, skills, dynamic tools, memories) as a single JSON file. */
@Singleton
public class SettingsTransfer {
    private static final Logger LOG = LoggerFactory.getLogger(SettingsTransfer.class);

    /**
     * What to put in an exported file. The API key and MCP env/header values are secrets, so they are left out unless
     * asked for.
     */
    public record ExportOptions(boolean skills, boolean dynamicTools, boolean apiKey, boolean memories) {
        public ExportOptions(boolean skills, boolean dynamicTools, boolean apiKey) {
            this(skills, dynamicTools, apiKey, true);
        }

        public static ExportOptions defaults() {
            return new ExportOptions(true, true, false, true);
        }
    }

    /**
     * What to take from an imported file. Dynamic tools are off by default because importing one compiles and runs
     * code from the file.
     */
    public record ImportOptions(boolean settings, boolean keepApiKey, boolean keepStorage, boolean skills,
            boolean dynamicTools, boolean memories) {
        public ImportOptions(boolean settings, boolean keepApiKey, boolean keepStorage, boolean skills,
                boolean dynamicTools) {
            this(settings, keepApiKey, keepStorage, skills, dynamicTools, true);
        }

        public static ImportOptions defaults() {
            return new ImportOptions(true, true, true, true, false, true);
        }
    }

    public record ImportReport(List<String> applied, List<String> errors) {
        public String summary() {
            StringBuilder sb = new StringBuilder();
            applied.forEach(a -> sb.append("✔ ").append(a).append('\n'));
            errors.forEach(e -> sb.append("✖ ").append(e).append('\n'));
            return sb.isEmpty() ? "Nothing was imported." : sb.toString().strip();
        }
    }

    private final SettingsStore store;
    private final SkillRepository skills;
    private final DynamicToolRegistry dynamicTools;
    private final Provider<Set<Tool>> builtins;
    private final MemoryService memory;

    public SettingsTransfer(SettingsStore store, SkillRepository skills, DynamicToolRegistry dynamicTools,
            Provider<Set<Tool>> builtins) {
        this(store, skills, dynamicTools, builtins, null);
    }

    /** @param memory {@code null} to leave memories out of exports and imports */
    @Inject
    public SettingsTransfer(SettingsStore store, SkillRepository skills, DynamicToolRegistry dynamicTools,
            Provider<Set<Tool>> builtins, MemoryService memory) {
        this.store = store;
        this.skills = skills;
        this.dynamicTools = dynamicTools;
        this.builtins = builtins;
        this.memory = memory;
    }

    private ObjectMapper mapper() {
        return store.mapper();
    }

    /** Builds a bundle from {@code settings} (e.g. the unsaved draft in the settings window). */
    public SettingsBundle export(HarnessSettings settings, ExportOptions options) {
        SettingsBundle bundle = new SettingsBundle();
        bundle.exportedAt = OffsetDateTime.now().toString();
        bundle.settings = mapper().convertValue(settings, HarnessSettings.class);
        bundle.settings.skills = null;
        if (!options.apiKey()) {
            bundle.settings.model.apiKey = "";
            // MCP env vars / headers may hold tokens; ${VAR} references are kept since they aren't secrets.
            bundle.settings.tools.mcpServers.forEach(server -> server.env.replaceAll(
                    (k, v) -> McpServerConfig.isReference(v) ? v : ""));
        }
        if (options.skills()) {
            for (SkillDefinition skill : skills.skills()) {
                SkillDefinition copy = skill.copy();
                copy.id = null;
                bundle.skills.add(copy);
            }
        }
        if (options.dynamicTools()) {
            dynamicTools.tools().stream().map(Tool::name).sorted(Comparator.naturalOrder())
                    .forEach(name -> dynamicTools.spec(name).ifPresent(bundle.dynamicTools::add));
        }
        if (options.memories() && memory != null) {
            MemorySettings.Backend backend = settings.memory == null ? MemorySettings.Backend.MARKDOWN
                    : settings.memory.backend;
            bundle.memories.addAll(memory.store(backend).list());
        }
        return bundle;
    }

    public void write(SettingsBundle bundle, Path file) throws IOException {
        Path parent = file.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        mapper().writeValue(file.toFile(), bundle);
        LOG.info("Exported harness settings to {} ({} skills, {} dynamic tools, {} memories)", file,
                bundle.skills.size(), bundle.dynamicTools.size(), bundle.memories.size());
    }

    /** Reads an exported bundle; a plain {@code settings.json} is accepted too. */
    public SettingsBundle read(Path file) throws IOException {
        JsonNode root;
        try {
            root = mapper().readTree(file.toFile());
        } catch (IOException e) {
            throw new IOException("Not a valid JSON file: " + e.getMessage(), e);
        }
        if (root == null || !root.isObject()) {
            throw new IOException("Expected a JSON object");
        }
        SettingsBundle bundle;
        if (root.has("format")) {
            String format = root.path("format").asText();
            if (!SettingsBundle.FORMAT.equals(format)) {
                throw new IOException("Unknown format '" + format + "' (expected " + SettingsBundle.FORMAT + ")");
            }
            int version = root.path("version").asInt(0);
            if (version < 1 || version > SettingsBundle.VERSION) {
                throw new IOException("Unsupported version " + version + " (this app reads up to "
                        + SettingsBundle.VERSION + ")");
            }
            bundle = mapper().treeToValue(root, SettingsBundle.class);
        } else if (looksLikeSettings((ObjectNode) root)) {
            bundle = new SettingsBundle();
            bundle.settings = mapper().treeToValue(root, HarnessSettings.class);
        } else {
            throw new IOException("This is neither a harness export nor a settings.json file");
        }
        if (bundle.settings == null) {
            bundle.settings = new HarnessSettings();
        }
        if (bundle.skills == null) {
            bundle.skills = new ArrayList<>();
        }
        if (bundle.dynamicTools == null) {
            bundle.dynamicTools = new ArrayList<>();
        }
        if (bundle.memories == null) {
            bundle.memories = new ArrayList<>();
        }
        // Legacy settings files kept skills inline.
        if (bundle.settings.skills != null) {
            bundle.skills.addAll(bundle.settings.skills);
            bundle.settings.skills = null;
        }
        return bundle;
    }

    private static boolean looksLikeSettings(ObjectNode root) {
        Set<String> known = Set.of("model", "systemPrompt", "skills", "context", "tools", "agentLoop", "guardrails",
                "approval", "reply", "storage", "speech", "memory");
        var names = root.fieldNames();
        while (names.hasNext()) {
            if (known.contains(names.next())) {
                return true;
            }
        }
        return false;
    }

    /** Applies the selected parts of {@code bundle}; one bad skill or tool doesn't stop the rest. */
    public ImportReport apply(SettingsBundle bundle, ImportOptions options) {
        List<String> applied = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        if (options.settings()) {
            HarnessSettings current = store.current();
            HarnessSettings imported = mapper().convertValue(bundle.settings, HarnessSettings.class);
            imported.skills = null;
            if (options.keepApiKey()) {
                imported.model.apiKey = current.model.apiKey;
            }
            if (options.keepStorage()) {
                imported.storage = mapper().convertValue(current.storage, StorageSettings.class);
            }
            store.replace(imported);
            applied.add("Settings (model, system prompt, context, tools, loop, guardrails, approval, reply, speech"
                    + (options.keepStorage() ? "" : ", storage") + ")");
        }
        if (options.skills()) {
            int count = 0;
            for (SkillDefinition skill : bundle.skills) {
                String name = skill == null || skill.name == null ? "(unnamed)" : skill.name;
                try {
                    SkillDefinition copy = skill.copy();
                    copy.id = null;
                    skills.save(copy);
                    count++;
                } catch (IOException | RuntimeException e) {
                    LOG.warn("Could not import skill '{}'", name, e);
                    errors.add("Skill '" + name + "': " + e.getMessage());
                }
            }
            if (count > 0) {
                applied.add(count + " skill(s)");
            }
        }
        if (options.dynamicTools()) {
            Set<String> reserved = builtins.get().stream().map(Tool::name).collect(Collectors.toSet());
            int count = 0;
            for (DynamicToolSpec spec : bundle.dynamicTools) {
                String name = spec == null || spec.name == null ? "(unnamed)" : spec.name;
                try {
                    dynamicTools.create(spec, reserved);
                    count++;
                } catch (DynamicToolException | RuntimeException e) {
                    LOG.warn("Could not import dynamic tool '{}'", name, e);
                    errors.add("Dynamic tool '" + name + "': " + e.getMessage());
                }
            }
            if (count > 0) {
                applied.add(count + " dynamic tool(s)");
            }
        }
        if (options.memories() && memory != null && !bundle.memories.isEmpty()) {
            MemoryStore target = memory.active();
            int count = 0;
            for (MemoryEntry entry : bundle.memories) {
                try {
                    if (entry == null || entry.text == null || entry.text.isBlank()) {
                        throw new IllegalArgumentException("empty memory");
                    }
                    target.save(entry);
                    count++;
                } catch (IOException | RuntimeException e) {
                    String id = entry == null ? "(null)" : String.valueOf(entry.id);
                    LOG.warn("Could not import memory {}", id, e);
                    errors.add("Memory '" + id + "': " + e.getMessage());
                }
            }
            if (count > 0) {
                applied.add(count + " memories (" + target.location() + ")");
            }
        }
        LOG.info("Imported harness settings: applied={}, errors={}", applied, errors);
        return new ImportReport(applied, errors);
    }
}
