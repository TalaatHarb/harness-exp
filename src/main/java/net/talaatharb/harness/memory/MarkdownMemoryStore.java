package net.talaatharb.harness.memory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Pattern;


import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * One {@code <id>.md} file per memory: a small front matter block (tags, source, timestamps) followed by the text.
 * Files are re-read on every {@link #list()}, so edits made outside the app show up after a refresh. A file without
 * front matter is a memory too (its text is the whole file).
 *
 * <pre>
 * ---
 * tags: ["preferences"]
 * source: agent
 * created: 2026-11-03T09:00:00Z
 * updated: 2026-11-03T09:00:00Z
 * ---
 *
 * The user prefers answers in British English.
 * </pre>
 */
public class MarkdownMemoryStore implements MemoryStore {
    public static final String EXTENSION = ".md";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String FENCE = "---";
    private static final Pattern VALID_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,119}");

    private final Path dir;

    public MarkdownMemoryStore(Path dir) {
        this.dir = dir.toAbsolutePath().normalize();
    }

    public Path dir() {
        return dir;
    }

    @Override
    public String location() {
        return dir.toString();
    }

    public Path file(String id) {
        return dir.resolve(checkId(id) + EXTENSION);
    }

    /** Ids become file names, so only simple names are accepted (no path separators or traversal). */
    static String checkId(String id) {
        if (id == null || !VALID_ID.matcher(id).matches() || id.contains("..")) {
            throw new IllegalArgumentException("Invalid memory id '" + id + "'");
        }
        return id;
    }

    @Override
    public synchronized List<MemoryEntry> list() {
        List<MemoryEntry> result = new ArrayList<>();
        if (Files.notExists(dir)) {
            return result;
        }
        try (DirectoryStream<Path> files = Files.newDirectoryStream(dir, "*" + EXTENSION)) {
            for (Path file : files) {
                String name = file.getFileName().toString();
                String id = name.substring(0, name.length() - EXTENSION.length());
                if (!VALID_ID.matcher(id).matches() || !Files.isRegularFile(file)) {
                    continue;
                }
                try {
                    result.add(read(file, id));
                } catch (IOException | RuntimeException e) {
                    throw new IllegalStateException("Could not read memory file " + file, e);
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Could not list memory folder " + dir, e);
        }
        result.sort(MemoryService.NEWEST_FIRST);
        return result;
    }

    @Override
    public synchronized Optional<MemoryEntry> get(String id) {
        try {
            Path file = file(id);
            return Files.isRegularFile(file) ? Optional.of(read(file, id)) : Optional.empty();
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public synchronized MemoryEntry save(MemoryEntry entry) throws IOException {
        MemoryEntry e = entry.copy();
        Instant now = Instant.now();
        if (e.id == null || e.id.isBlank()) {
            e.id = newId(e.text, this::exists);
        }
        Path file = file(e.id);
        if (e.createdAt == null) {
            e.createdAt = get(e.id).map(old -> old.createdAt).orElse(now);
        }
        e.updatedAt = now;
        Files.createDirectories(dir);
        Path tmp = dir.resolve("." + e.id + ".tmp");
        Files.writeString(tmp, format(e), StandardCharsets.UTF_8);
        try {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException ex) {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        }
        return e;
    }

    private boolean exists(String id) {
        return Files.exists(dir.resolve(id + EXTENSION));
    }

    @Override
    public synchronized boolean delete(String id) throws IOException {
        try {
            return Files.deleteIfExists(file(id));
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static MemoryEntry read(Path file, String id) throws IOException {
        MemoryEntry e = parse(Files.readString(file, StandardCharsets.UTF_8));
        e.id = id;
        if (e.updatedAt == null) {
            e.updatedAt = Files.getLastModifiedTime(file).toInstant();
        }
        if (e.createdAt == null) {
            e.createdAt = e.updatedAt;
        }
        return e;
    }

    static MemoryEntry parse(String text) {
        String content = text.replace("\r\n", "\n").replace('\r', '\n');
        if (content.startsWith("\uFEFF")) {
            content = content.substring(1);
        }
        MemoryEntry e = new MemoryEntry();
        String[] lines = content.split("\n", -1);
        int end = -1;
        if (lines.length > 0 && lines[0].strip().equals(FENCE)) {
            for (int i = 1; i < lines.length; i++) {
                if (lines[i].strip().equals(FENCE)) {
                    end = i;
                    break;
                }
            }
        }
        if (end < 0) {
            e.text = content.strip();
            return e;
        }
        for (int i = 1; i < end; i++) {
            int colon = lines[i].indexOf(':');
            if (colon <= 0) {
                continue;
            }
            String key = lines[i].substring(0, colon).strip().toLowerCase(Locale.ROOT);
            String value = lines[i].substring(colon + 1).strip();
            switch (key) {
                case "tags" -> e.tags = tags(value);
                case "source" -> e.source = unquote(value);
                case "created" -> e.createdAt = instant(value);
                case "updated" -> e.updatedAt = instant(value);
                default -> {
                    // unknown keys are ignored
                }
            }
        }
        e.text = String.join("\n", List.of(lines).subList(end + 1, lines.length)).strip();
        return e;
    }

    static String format(MemoryEntry e) {
        try {
            return FENCE + "\n"
                    + "tags: " + JSON.writeValueAsString(MemoryEntry.cleanTags(e.tags)) + "\n"
                    + "source: " + (e.source == null ? MemoryEntry.SOURCE_USER : e.source) + "\n"
                    + "created: " + e.createdAt + "\n"
                    + "updated: " + e.updatedAt + "\n"
                    + FENCE + "\n\n"
                    + (e.text == null ? "" : e.text.strip()) + "\n";
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    private static List<String> tags(String value) {
        String v = value.strip();
        if (v.startsWith("[")) {
            try {
                return MemoryEntry.cleanTags(JSON.readValue(v, new TypeReference<List<String>>() {
                }));
            } catch (IOException ex) {
                v = v.replaceAll("[\\[\\]\"']", "");
            }
        }
        return MemoryEntry.parseTags(v);
    }

    private static Instant instant(String value) {
        try {
            return Instant.parse(unquote(value));
        } catch (DateTimeParseException ex) {
            return null;
        }
    }

    private static String unquote(String value) {
        String v = value.strip();
        return v.length() >= 2 && (v.startsWith("\"") && v.endsWith("\"") || v.startsWith("'") && v.endsWith("'"))
                ? v.substring(1, v.length() - 1) : v;
    }

    /** Readable, file-safe id from the first words of the text plus a short random suffix, e.g. {@code user-prefers-british-3fa9c1}. */
    static String newId(String text, java.util.function.Predicate<String> taken) {
        String slug = (text == null ? "" : text).toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-+|-+$", "");
        String[] words = slug.split("-");
        StringBuilder sb = new StringBuilder();
        for (String w : words) {
            if (w.isEmpty() || sb.length() + w.length() > 40) {
                break;
            }
            sb.append(sb.isEmpty() ? "" : "-").append(w);
        }
        String base = sb.isEmpty() ? "memory" : sb.toString();
        String id;
        do {
            id = base + "-" + String.format("%06x", ThreadLocalRandom.current().nextInt(0x1000000));
        } while (taken.test(id));
        return id;
    }
}
