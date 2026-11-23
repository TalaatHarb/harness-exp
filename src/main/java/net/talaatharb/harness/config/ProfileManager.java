package net.talaatharb.harness.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import net.talaatharb.harness.prompt.SystemPromptSettings;
import net.talaatharb.harness.skills.SkillDefinition;
import net.talaatharb.harness.skills.SkillFiles;

/** Persistent profile selection. The legacy settings file remains the Default profile. */
public final class ProfileManager {
    public static final String DEFAULT = "default";
    private static final Logger LOG = LoggerFactory.getLogger(ProfileManager.class);
    private final Path defaultSettings;
    private final Path profilesDir;
    private final Path selectionFile;
    private final ObjectMapper mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT)
            .registerModule(new JavaTimeModule()).disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    private String running = DEFAULT;

    public ProfileManager(Path defaultSettings) {
        this.defaultSettings = defaultSettings.toAbsolutePath().normalize();
        Path parent = this.defaultSettings.getParent();
        String fileName = this.defaultSettings.getFileName().toString();
        this.profilesDir = parent.resolve(fileName.equals("settings.json") ? "profiles" : fileName + ".profiles");
        this.selectionFile = profilesDir.resolve("active-profile");
    }

    /** Called once before constructing the application services. */
    public void initialize() throws IOException {
        Files.createDirectories(profilesDir);
        if (!Files.exists(defaultSettings)) {
            writeNewSettings(defaultSettings, new HarnessSettings());
        }
        ensureBuiltin("learning");
        ensureBuiltin("coding");
        running = selected();
        requireProfile(running);
    }

    public String running() {
        return running;
    }

    public Path runningSettingsPath() {
        return settingsPath(running);
    }

    public String selected() throws IOException {
        if (!Files.exists(selectionFile)) {
            return DEFAULT;
        }
        String id;
        try {
            id = validateId(Files.readString(selectionFile, StandardCharsets.UTF_8).strip());
        } catch (IllegalArgumentException e) {
            throw new IOException("Invalid profile selection in " + selectionFile, e);
        }
        requireProfile(id);
        return id;
    }

    public List<String> profiles() throws IOException {
        List<String> result = new ArrayList<>();
        result.add(DEFAULT);
        if (Files.isDirectory(profilesDir)) {
            try (var entries = Files.list(profilesDir)) {
                entries.filter(p -> Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS))
                        .filter(p -> validId(p.getFileName().toString()))
                        .filter(p -> !p.getFileName().toString().equals(DEFAULT))
                        .filter(p -> Files.isRegularFile(p.resolve("settings.json"), LinkOption.NOFOLLOW_LINKS))
                        .map(p -> p.getFileName().toString())
                        .sorted().forEach(result::add);
            }
        }
        return List.copyOf(result);
    }

    /** Creates an independent profile with fresh settings and default storage. */
    public String create(String name) throws IOException {
        String id = validateId(name == null ? "" : name.strip().toLowerCase(Locale.ROOT));
        if (DEFAULT.equals(id)) {
            throw new IllegalArgumentException("The default profile already exists");
        }
        Path directory = profilesDir.resolve(id);
        Files.createDirectories(profilesDir);
        Files.createDirectory(directory);
        try {
            writeNewSettings(directory.resolve("settings.json"), new HarnessSettings());
        } catch (IOException | RuntimeException e) {
            try {
                Files.delete(directory);
            } catch (IOException cleanup) {
                e.addSuppressed(cleanup);
            }
            throw e;
        }
        return id;
    }

    /** Selects a profile for the next process start; does not change the running services. */
    public void select(String id) throws IOException {
        id = validateId(id);
        requireProfile(id);
        Files.createDirectories(profilesDir);
        Path temp = Files.createTempFile(profilesDir, "active-", ".tmp");
        try {
            Files.writeString(temp, id + "\n", StandardCharsets.UTF_8);
            try {
                Files.move(temp, selectionFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, selectionFile, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    public Path settingsPath(String id) {
        id = validateId(id);
        return DEFAULT.equals(id) ? defaultSettings : profilesDir.resolve(id).resolve("settings.json");
    }

    private void requireProfile(String id) throws IOException {
        Path path = settingsPath(id);
        boolean regular = DEFAULT.equals(id) ? Files.isRegularFile(path)
                : Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS);
        if ((!DEFAULT.equals(id) && !Files.isDirectory(path.getParent(), LinkOption.NOFOLLOW_LINKS)) || !regular) {
            throw new IOException("Profile '" + id + "' has no accessible settings file: " + path);
        }
        if (mapper.readValue(path.toFile(), HarnessSettings.class) == null) {
            throw new IOException("Profile '" + id + "' has an empty settings file: " + path);
        }
    }

    private void ensureBuiltin(String id) throws IOException {
        Path dir = profilesDir.resolve(id);
        if (Files.exists(dir, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        Files.createDirectory(dir);
        HarnessSettings settings = new HarnessSettings();
        if (id.equals("learning")) {
            settings.systemPrompt.template = """
                    You are a patient learning assistant. Help the learner build understanding step by step.
                    Ask about their goals and prior knowledge, adapt explanations to their answers, and offer
                    practice and constructive feedback. Do not assume an answer is understood without checking.
                    Today is {{date}} and the local time is {{time}}. You are served by model {{model}}.
                    Be accurate and acknowledge uncertainty.
                    """ + SystemPromptSettings.DIAGRAM_GUIDANCE;
            settings.tools.disabledTools.addAll(Set.of("edit_file", "run_native_shell", "run_python_code",
                    "create_tool"));
        } else {
            settings.systemPrompt.template = """
                    You are a software engineering assistant. Inspect relevant context before making changes.
                    Make focused changes, preserve existing behavior, and use appropriate tests to verify them.
                    Ask for clarification when requirements or destructive actions are uncertain.
                    Today is {{date}} and the local time is {{time}}. You are served by model {{model}}.
                    Be concise and accurate, and report failures and uncertainty.
                    """ + SystemPromptSettings.DIAGRAM_GUIDANCE;
        }
        try {
            writeNewSettings(dir.resolve("settings.json"), settings);
        } catch (IOException | RuntimeException e) {
            try {
                Files.delete(dir);
            } catch (IOException cleanup) {
                e.addSuppressed(cleanup);
            }
            throw e;
        }
        seedSkills(id, dir.resolve(StoragePaths.SKILLS));
    }

    /**
     * Gives a new built-in profile its bundled skills. On failure the folder is removed so the skill repository seeds
     * the general defaults instead; the profile itself stays usable.
     */
    private void seedSkills(String id, Path skillsDir) {
        try {
            for (SkillDefinition skill : SkillFiles.bundledForProfile(id)) {
                Path file = skillsDir.resolve(skill.id).resolve(SkillFiles.FILE_NAME);
                Files.createDirectories(file.getParent());
                Files.writeString(file, SkillFiles.format(skill), StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
            }
        } catch (IOException | RuntimeException e) {
            LOG.warn("Could not seed skills for profile '{}'", id, e);
            try (var paths = Files.walk(skillsDir)) {
                for (Path p : paths.sorted(java.util.Comparator.reverseOrder()).toList()) {
                    Files.deleteIfExists(p);
                }
            } catch (IOException | RuntimeException cleanup) {
                LOG.warn("Could not remove partially seeded skills in {}", skillsDir, cleanup);
            }
        }
    }

    private void writeNewSettings(Path path, HarnessSettings settings) throws IOException {
        Files.createDirectories(path.getParent());
        Path temp = Files.createTempFile(path.getParent(), "settings-", ".tmp");
        try {
            mapper.writeValue(temp.toFile(), settings);
            Files.move(temp, path);
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    private static boolean validId(String id) {
        return id != null && id.matches("[a-z0-9][a-z0-9_-]{0,47}");
    }

    private static String validateId(String id) {
        if (!validId(id)) {
            throw new IllegalArgumentException(
                    "Profile names must be 1-48 lowercase letters, digits, hyphens or underscores, starting with a letter or digit");
        }
        return id;
    }
}
