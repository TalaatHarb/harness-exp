package net.talaatharb.harness.skills;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Reads and writes {@code SKILL.md} files: a small YAML-style front matter block followed by the instructions.
 *
 * <pre>
 * ---
 * name: "SQL reviewer"
 * description: "Reviews SQL queries"
 * triggers: ["sql", "query"]
 * enabled: true
 * ---
 *
 * When reviewing SQL, ...
 * </pre>
 *
 * Values may be plain, single- or double-quoted; triggers may be a {@code [..]} list, a comma separated string, or
 * a block list of {@code - item} lines. Unknown keys are ignored. A file without front matter becomes an always-on
 * skill named after its folder.
 */
public final class SkillFiles {
    public static final String FILE_NAME = "SKILL.md";
    static final String BUNDLED_ROOT = "/net/talaatharb/harness/skills/defaults/";
    static final List<String> BUNDLED_IDS = List.of("code-reviewer", "summarizer");
    private static final String FENCE = "---";
    private static final int MAX_ID_CHARS = 60;
    private static final Set<String> WINDOWS_RESERVED = Set.of("con", "prn", "aux", "nul", "com1", "com2", "com3",
            "com4", "com5", "com6", "com7", "com8", "com9", "lpt1", "lpt2", "lpt3", "lpt4", "lpt5", "lpt6", "lpt7",
            "lpt8", "lpt9");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private SkillFiles() {
    }

    public static SkillDefinition parse(String text, String fallbackName) {
        String content = text == null ? "" : text.replace("\r\n", "\n").replace('\r', '\n');
        if (content.startsWith("\uFEFF")) {
            content = content.substring(1);
        }
        SkillDefinition skill = new SkillDefinition();
        skill.name = fallbackName;
        List<String> lines = Arrays.asList(content.split("\n", -1));
        int end = -1;
        if (!lines.isEmpty() && lines.get(0).strip().equals(FENCE)) {
            for (int i = 1; i < lines.size(); i++) {
                if (lines.get(i).strip().equals(FENCE)) {
                    end = i;
                    break;
                }
            }
        }
        if (end < 0) {
            skill.instructions = content.strip();
            return skill;
        }
        String listKey = null;
        List<String> blockList = null;
        for (int i = 1; i < end; i++) {
            String line = lines.get(i);
            String trimmed = line.strip();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                continue;
            }
            if (trimmed.startsWith("- ") && listKey != null) {
                blockList.add(unquote(trimmed.substring(2).strip()));
                continue;
            }
            finishBlockList(skill, listKey, blockList);
            listKey = null;
            int colon = line.indexOf(':');
            if (colon <= 0) {
                continue;
            }
            String key = line.substring(0, colon).strip().toLowerCase(Locale.ROOT);
            String value = line.substring(colon + 1).strip();
            if (value.isEmpty()) {
                listKey = key;
                blockList = new ArrayList<>();
                continue;
            }
            switch (key) {
                case "name":
                    skill.name = unquote(value);
                    break;
                case "description":
                    skill.description = unquote(value);
                    break;
                case "triggers":
                    skill.triggers = parseList(value);
                    break;
                case "enabled":
                    skill.enabled = !Set.of("false", "no", "off", "0").contains(unquote(value).toLowerCase(Locale.ROOT));
                    break;
                default:
                    break;
            }
        }
        finishBlockList(skill, listKey, blockList);
        skill.instructions = String.join("\n", lines.subList(end + 1, lines.size())).strip();
        if (skill.name == null || skill.name.isBlank()) {
            skill.name = fallbackName;
        }
        return skill;
    }

    private static void finishBlockList(SkillDefinition skill, String key, List<String> items) {
        if ("triggers".equals(key) && items != null) {
            skill.triggers = clean(items);
        }
    }

    public static String format(SkillDefinition skill) {
        try {
            return FENCE + "\n"
                    + "name: " + MAPPER.writeValueAsString(oneLine(skill.name)) + "\n"
                    + "description: " + MAPPER.writeValueAsString(oneLine(skill.description)) + "\n"
                    + "triggers: " + MAPPER.writeValueAsString(clean(skill.triggers)) + "\n"
                    + "enabled: " + skill.enabled + "\n"
                    + FENCE + "\n\n"
                    + (skill.instructions == null ? "" : skill.instructions.strip()) + "\n";
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Folder-safe id derived from a skill name, e.g. {@code "SQL reviewer!"} → {@code sql-reviewer}. */
    public static String slug(String name) {
        String slug = (name == null ? "" : name).toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-+|-+$", "");
        if (slug.length() > MAX_ID_CHARS) {
            slug = slug.substring(0, MAX_ID_CHARS).replaceAll("-+$", "");
        }
        if (slug.isEmpty()) {
            return "skill";
        }
        return WINDOWS_RESERVED.contains(slug) ? slug + "-skill" : slug;
    }

    static List<SkillDefinition> bundledDefaults() {
        List<SkillDefinition> skills = new ArrayList<>();
        for (String id : BUNDLED_IDS) {
            try (InputStream in = SkillFiles.class.getResourceAsStream(BUNDLED_ROOT + id + "/" + FILE_NAME)) {
                if (in == null) {
                    throw new IllegalStateException("Missing bundled skill " + id);
                }
                SkillDefinition skill = parse(new String(in.readAllBytes(), StandardCharsets.UTF_8), id);
                skill.id = id;
                skills.add(skill);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        return skills;
    }

    static List<String> parseList(String value) {
        String v = value.strip();
        if (v.startsWith("[")) {
            try {
                return clean(MAPPER.readValue(v, new TypeReference<List<String>>() {
                }));
            } catch (IOException e) {
                v = v.substring(1, v.endsWith("]") ? v.length() - 1 : v.length());
            }
        }
        List<String> items = new ArrayList<>();
        for (String part : v.split(",")) {
            items.add(unquote(part.strip()));
        }
        return clean(items);
    }

    static List<String> clean(List<String> items) {
        if (items == null) {
            return new ArrayList<>();
        }
        return new ArrayList<>(items.stream().filter(t -> t != null && !t.isBlank()).map(String::strip)
                .distinct().toList());
    }

    private static String unquote(String value) {
        String v = value.strip();
        if (v.length() >= 2 && v.startsWith("\"") && v.endsWith("\"")) {
            try {
                return MAPPER.readValue(v, String.class);
            } catch (IOException e) {
                return v.substring(1, v.length() - 1);
            }
        }
        if (v.length() >= 2 && v.startsWith("'") && v.endsWith("'")) {
            return v.substring(1, v.length() - 1).replace("''", "'");
        }
        return v;
    }

    private static String oneLine(String text) {
        return text == null ? "" : text.replaceAll("\\s+", " ").strip();
    }
}
