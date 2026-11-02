package net.talaatharb.harness.config;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** The data locations in effect, resolved from {@link StorageSettings} once at startup. */
public record StoragePaths(Path skillsDir, Path dynamicToolsDir, Path historyDb, Path modelsDir) {
    private static final Logger LOG = LoggerFactory.getLogger(StoragePaths.class);

    public static final String SKILLS = "skills";
    public static final String DYNAMIC_TOOLS = "dynamic-tools";
    public static final String HISTORY_DB = "history.db";
    public static final String MODELS = "models";

    public static StoragePaths resolve(Path settingsPath, StorageSettings storage) {
        Path base = settingsDir(settingsPath);
        StorageSettings s = storage == null ? new StorageSettings() : storage;
        return new StoragePaths(
                resolve(base, s.skillsDir, SKILLS),
                resolve(base, s.dynamicToolsDir, DYNAMIC_TOOLS),
                resolve(base, s.historyDb, HISTORY_DB),
                resolve(base, s.modelsDir, MODELS));
    }

    public static StoragePaths defaults(Path settingsPath) {
        return resolve(settingsPath, null);
    }

    /** Folder of {@code settings.json}; the base for default and relative locations. */
    public static Path settingsDir(Path settingsPath) {
        Path parent = settingsPath == null ? null : settingsPath.toAbsolutePath().getParent();
        return (parent == null ? Paths.get(".") : parent).toAbsolutePath().normalize();
    }

    /**
     * @param value configured path; blank means {@code base/defaultName}
     * @return the absolute path; falls back to the default (with a warning) if the value isn't a valid path
     */
    public static Path resolve(Path base, String value, String defaultName) {
        Path fallback = base.resolve(defaultName).normalize();
        String text = value == null ? "" : value.strip();
        if (text.isEmpty()) {
            return fallback;
        }
        if (text.equals("~") || text.startsWith("~/") || text.startsWith("~\\")) {
            text = System.getProperty("user.home") + text.substring(1);
        }
        try {
            return base.resolve(text).toAbsolutePath().normalize();
        } catch (InvalidPathException e) {
            LOG.warn("Invalid storage path '{}', using {} instead: {}", value, fallback, e.getMessage());
            return fallback;
        }
    }
}
