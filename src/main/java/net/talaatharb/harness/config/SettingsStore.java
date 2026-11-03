package net.talaatharb.harness.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.google.inject.name.Named;

/**
 * Loads and saves {@link HarnessSettings} as JSON. Consumers read
 * {@link #current()} at the start of each operation so edits apply to the
 * next agent run without restarting.
 */
@Singleton
public class SettingsStore {
    public static final String SETTINGS_PATH = "settingsPath";
    private static final Logger LOG = LoggerFactory.getLogger(SettingsStore.class);

    private final Path path;
    private final ObjectMapper mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT)
            .registerModule(new JavaTimeModule()).disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    private final List<Runnable> listeners = new CopyOnWriteArrayList<>();
    private volatile HarnessSettings current;

    @Inject
    public SettingsStore(@Named(SETTINGS_PATH) Path path) {
        this.path = path;
        this.current = load();
    }

    private HarnessSettings load() {
        if (path == null || !Files.isRegularFile(path)) {
            return new HarnessSettings();
        }
        try {
            return mapper.readValue(path.toFile(), HarnessSettings.class);
        } catch (IOException e) {
            LOG.warn("Could not read settings from {}, using defaults", path, e);
            return new HarnessSettings();
        }
    }

    public HarnessSettings current() {
        return current;
    }

    /** Deep copy for editing in the settings window without affecting running agents. */
    public HarnessSettings copy() {
        return mapper.convertValue(current, HarnessSettings.class);
    }

    public void replace(HarnessSettings settings) {
        current = mapper.convertValue(settings, HarnessSettings.class);
        save();
        listeners.forEach(Runnable::run);
    }

    public void save() {
        if (path == null) {
            return;
        }
        try {
            Files.createDirectories(path.toAbsolutePath().getParent());
            Path tmp = path.resolveSibling(path.getFileName() + ".tmp");
            mapper.writeValue(tmp.toFile(), current);
            Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            LOG.error("Could not save settings to {}", path, e);
        }
    }

    public void addListener(Runnable listener) {
        listeners.add(listener);
    }

    public ObjectMapper mapper() {
        return mapper;
    }
}
