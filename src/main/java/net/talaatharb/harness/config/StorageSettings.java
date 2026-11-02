package net.talaatharb.harness.config;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Where the harness keeps its data. Blank = default location next to {@code settings.json}; relative paths are
 * resolved against the settings folder and {@code ~} expands to the user home. Read once at startup.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class StorageSettings {
    /** Folder of {@code <id>/SKILL.md} skills. */
    public String skillsDir = "";
    /** Folder of agent-built dynamic tools ({@code <name>.json}). */
    public String dynamicToolsDir = "";
    /** MapDB conversation history file. */
    public String historyDb = "";
    /** Downloaded local models, e.g. the Whistle speech-to-text engine and model. */
    public String modelsDir = "";
}
