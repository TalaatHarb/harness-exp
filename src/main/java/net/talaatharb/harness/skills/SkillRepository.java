package net.talaatharb.harness.skills;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.google.inject.name.Named;

import net.talaatharb.harness.config.HarnessSettings;
import net.talaatharb.harness.config.SettingsStore;

/**
 * The skills folder is the source of truth for skills: one {@code <id>/SKILL.md} per skill. Skills are read at
 * startup and on {@link #reload()} (the Refresh button), and written by the settings UI and the
 * {@code create_skill} tool. On first start the folder is seeded with the bundled defaults, and skills that older
 * versions kept in {@code settings.json} are moved into it.
 */
@Singleton
public class SkillRepository {
    public static final String SKILLS_DIR = "skillsDir";
    public static final int MAX_SKILLS = 100;
    public static final int MAX_NAME_CHARS = 80;
    public static final int MAX_DESCRIPTION_CHARS = 500;
    public static final int MAX_INSTRUCTIONS_CHARS = 10_000;
    public static final int MAX_TRIGGERS = 20;
    public static final int MAX_TRIGGER_CHARS = 80;
    private static final Logger LOG = LoggerFactory.getLogger(SkillRepository.class);

    private final Path dir;
    private final List<Runnable> listeners = new CopyOnWriteArrayList<>();
    private volatile List<SkillDefinition> skills = List.of();
    private volatile List<String> problems = List.of();

    @Inject
    public SkillRepository(@Named(SKILLS_DIR) Path dir, SettingsStore store) {
        this.dir = dir.toAbsolutePath().normalize();
        initialize(store);
        reload();
    }

    private void initialize(SettingsStore store) {
        boolean firstStart = !Files.isDirectory(dir);
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            LOG.error("Could not create the skills folder {}", dir, e);
            return;
        }
        List<SkillDefinition> legacy = store == null ? null : store.current().skills;
        if (legacy != null) {
            migrate(legacy, store);
        } else if (firstStart) {
            for (SkillDefinition skill : SkillFiles.bundledDefaults()) {
                writeQuietly(skill.id, skill);
            }
            LOG.info("Seeded the skills folder {} with the default skills", dir);
        }
    }

    /** Moves skills from settings.json (pre skills-folder versions) into the folder, then drops them from settings. */
    private void migrate(List<SkillDefinition> legacy, SettingsStore store) {
        int moved = 0;
        for (SkillDefinition skill : legacy) {
            String id = SkillFiles.slug(skill.name);
            if (!Files.exists(file(id)) && writeQuietly(id, skill)) {
                moved++;
            }
        }
        HarnessSettings updated = store.copy();
        updated.skills = null;
        store.replace(updated);
        LOG.info("Moved {} skill(s) from settings.json into {}", moved, dir);
    }

    private boolean writeQuietly(String id, SkillDefinition skill) {
        try {
            write(id, skill);
            return true;
        } catch (IOException e) {
            LOG.error("Could not write skill '{}' to {}", skill.name, dir, e);
            return false;
        }
    }

    public Path dir() {
        return dir;
    }

    /** Copies of the currently loaded skills, sorted by name. */
    public List<SkillDefinition> skills() {
        return skills.stream().map(SkillDefinition::copy).toList();
    }

    public Optional<SkillDefinition> find(String id) {
        return skills.stream().filter(s -> s.id.equals(id)).findFirst().map(SkillDefinition::copy);
    }

    public Optional<SkillDefinition> findByName(String name) {
        String wanted = name == null ? "" : name.strip();
        return skills.stream().filter(s -> s.name.equalsIgnoreCase(wanted)).findFirst().map(SkillDefinition::copy);
    }

    /** Folders that couldn't be read during the last {@link #reload()}. */
    public List<String> problems() {
        return problems;
    }

    /** Re-reads the folder, picking up skills added, edited or removed outside the app. */
    public synchronized List<SkillDefinition> reload() {
        List<SkillDefinition> loaded = new ArrayList<>();
        List<String> issues = new ArrayList<>();
        if (Files.isDirectory(dir)) {
            try (DirectoryStream<Path> folders = Files.newDirectoryStream(dir, Files::isDirectory)) {
                for (Path folder : folders) {
                    Path file = folder.resolve(SkillFiles.FILE_NAME);
                    if (!Files.isRegularFile(file)) {
                        continue;
                    }
                    String id = folder.getFileName().toString();
                    try {
                        SkillDefinition skill = SkillFiles.parse(Files.readString(file, StandardCharsets.UTF_8), id);
                        skill.id = id;
                        validate(skill);
                        loaded.add(skill);
                    } catch (IOException | IllegalArgumentException e) {
                        LOG.warn("Skipping skill {}: {}", file, e.getMessage());
                        issues.add(id + ": " + e.getMessage());
                    }
                }
            } catch (IOException e) {
                LOG.error("Could not read the skills folder {}", dir, e);
                issues.add("Could not read " + dir + ": " + e.getMessage());
            }
        }
        loaded.sort(Comparator.comparing((SkillDefinition s) -> s.name.toLowerCase(Locale.ROOT))
                .thenComparing(s -> s.id));
        skills = List.copyOf(loaded);
        problems = List.copyOf(issues);
        LOG.info("Loaded {} skill(s) from {}{}", loaded.size(), dir,
                issues.isEmpty() ? "" : " (" + issues.size() + " skipped)");
        listeners.forEach(Runnable::run);
        return skills();
    }

    /**
     * Creates or updates a skill. A skill with an {@code id} updates that folder; otherwise a skill with the same
     * name (case-insensitive) is replaced, or a new folder is created.
     *
     * @return the stored skill, with its id
     * @throws IllegalArgumentException for invalid skills, duplicate names or too many skills
     * @throws IOException              if the file can't be written
     */
    public synchronized SkillDefinition save(SkillDefinition input) throws IOException {
        SkillDefinition skill = normalize(input);
        validate(skill);
        Optional<SkillDefinition> sameName = skills.stream()
                .filter(s -> s.name.equalsIgnoreCase(skill.name)).findFirst();
        if (skill.id == null) {
            if (sameName.isPresent()) {
                skill.id = sameName.get().id;
            } else {
                if (skills.size() >= MAX_SKILLS) {
                    throw new IllegalArgumentException("Too many skills (max " + MAX_SKILLS + ")");
                }
                skill.id = uniqueId(SkillFiles.slug(skill.name));
            }
        } else {
            checkId(skill.id);
            if (sameName.isPresent() && !sameName.get().id.equals(skill.id)) {
                throw new IllegalArgumentException("Another skill is already named '" + skill.name + "'");
            }
        }
        write(skill.id, skill);
        LOG.info("Saved skill '{}' to {}", skill.name, file(skill.id));
        reload();
        return skill.copy();
    }

    /** Deletes the skill's {@code SKILL.md}, and its folder when nothing else is in it. */
    public synchronized boolean delete(String id) throws IOException {
        checkId(id);
        boolean removed = Files.deleteIfExists(file(id));
        Path folder = dir.resolve(id);
        try (DirectoryStream<Path> rest = Files.newDirectoryStream(folder)) {
            if (!rest.iterator().hasNext()) {
                Files.delete(folder);
            }
        } catch (java.nio.file.NoSuchFileException ignored) {
            // already gone
        }
        LOG.info("Deleted skill {}", id);
        reload();
        return removed;
    }

    public void addListener(Runnable listener) {
        listeners.add(listener);
    }

    public Path file(String id) {
        return dir.resolve(id).resolve(SkillFiles.FILE_NAME);
    }

    private void write(String id, SkillDefinition skill) throws IOException {
        Path folder = dir.resolve(id);
        Files.createDirectories(folder);
        Path tmp = folder.resolve(SkillFiles.FILE_NAME + ".tmp");
        Files.writeString(tmp, SkillFiles.format(skill), StandardCharsets.UTF_8);
        Files.move(tmp, file(id), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    private String uniqueId(String base) {
        String id = base;
        for (int i = 2; Files.exists(dir.resolve(id)); i++) {
            id = base + "-" + i;
        }
        return id;
    }

    /** Ids come from folder names; never let one escape the skills folder. */
    private void checkId(String id) {
        if (id == null || id.isBlank() || !dir.resolve(id).normalize().getParent().equals(dir)) {
            throw new IllegalArgumentException("Invalid skill id '" + id + "'");
        }
    }

    private static SkillDefinition normalize(SkillDefinition input) {
        SkillDefinition skill = input.copy();
        skill.name = skill.name == null ? "" : skill.name.replaceAll("\\s+", " ").strip();
        skill.description = skill.description == null ? "" : skill.description.replaceAll("\\s+", " ").strip();
        skill.instructions = skill.instructions == null ? "" : skill.instructions.strip();
        skill.triggers = SkillFiles.clean(skill.triggers);
        return skill;
    }

    static void validate(SkillDefinition skill) {
        if (skill.name == null || skill.name.isBlank()) {
            throw new IllegalArgumentException("A skill needs a name");
        }
        if (skill.name.length() > MAX_NAME_CHARS) {
            throw new IllegalArgumentException("Skill name is longer than " + MAX_NAME_CHARS + " characters");
        }
        if (skill.description != null && skill.description.length() > MAX_DESCRIPTION_CHARS) {
            throw new IllegalArgumentException("Description is longer than " + MAX_DESCRIPTION_CHARS + " characters");
        }
        if (skill.instructions == null || skill.instructions.isBlank()) {
            throw new IllegalArgumentException("Skill '" + skill.name + "' has no instructions");
        }
        if (skill.instructions.length() > MAX_INSTRUCTIONS_CHARS) {
            throw new IllegalArgumentException("Instructions are longer than " + MAX_INSTRUCTIONS_CHARS + " characters");
        }
        List<String> triggers = skill.triggers == null ? List.of() : skill.triggers;
        if (triggers.size() > MAX_TRIGGERS) {
            throw new IllegalArgumentException("Too many triggers (max " + MAX_TRIGGERS + ")");
        }
        if (triggers.stream().anyMatch(t -> t.length() > MAX_TRIGGER_CHARS)) {
            throw new IllegalArgumentException("Triggers must be at most " + MAX_TRIGGER_CHARS + " characters");
        }
    }
}
