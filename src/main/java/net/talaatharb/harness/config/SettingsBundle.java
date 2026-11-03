package net.talaatharb.harness.config;

import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import net.talaatharb.harness.memory.MemoryEntry;
import net.talaatharb.harness.skills.SkillDefinition;
import net.talaatharb.harness.tools.dynamic.DynamicToolSpec;

/**
 * Everything that makes up a harness setup in one JSON document: the settings plus the skills folder contents, the
 * dynamic tools and the memories. Written by "Export…" and read by "Import…" in the Harness window.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class SettingsBundle {
    public static final String FORMAT = "harness-exp/settings";
    public static final int VERSION = 1;

    public String format = FORMAT;
    public int version = VERSION;
    public String exportedAt;
    public HarnessSettings settings = new HarnessSettings();
    public List<SkillDefinition> skills = new ArrayList<>();
    public List<DynamicToolSpec> dynamicTools = new ArrayList<>();
    public List<MemoryEntry> memories = new ArrayList<>();
}
