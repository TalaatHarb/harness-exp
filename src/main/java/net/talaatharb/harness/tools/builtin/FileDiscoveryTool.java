package net.talaatharb.harness.tools.builtin;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.ObjectMapper;
import net.talaatharb.harness.tools.RiskLevel;
import net.talaatharb.harness.tools.Tool;
import net.talaatharb.harness.tools.ToolResult;

/** Read-only discovery beneath an explicitly selected directory; does not follow symbolic links. */
public class FileDiscoveryTool implements Tool {
    static final int MAX_ENTRIES = 10_000;
    static final int MAX_FILE_BYTES = 256 * 1024;
    static final int MAX_SEARCH_BYTES = 8 * 1024 * 1024;
    static final int MAX_OUTPUT_CHARS = 20_000;

    @Override public String name() { return "file_discovery"; }
    @Override public RiskLevel risk() { return RiskLevel.SENSITIVE; }
    @Override public String description() {
        return "Lists a folder (list), finds filenames (find), or searches literal text in UTF-8 files (search). "
                + "Requires a root path. Returns JSON paths, metadata and line-numbered snippets. "
                + "Hidden files excluded by default; no symlink traversal. Search reads at most 256 KiB/file, "
                + "8 MiB total; at most 10,000 entries and 20,000 output characters. Reports incomplete searches.";
    }
    @Override public String argumentsHint() {
        return "{\"path\":\"folder\",\"operation\":\"list|find|search\",\"glob\":\"*.txt (optional; filename or "
                + "relative path)\",\"query\":\"literal text required for search\",\"caseSensitive\":false,"
                + "\"includeHidden\":false,\"maxDepth\":5,\"maxResults\":100} "
                + "list always uses depth 1; find/search default depth 5 (max 32); maxResults max 500.";
    }

    @Override
    public ToolResult execute(Map<String, Object> arguments) throws IOException {
        try {
            if (arguments == null) {
                throw new IllegalArgumentException("Arguments are required");
            }
            Path requested = Path.of(ToolArguments.string(arguments, "path", null)).toAbsolutePath().normalize();
            if (Files.isSymbolicLink(requested) || !Files.isDirectory(requested, LinkOption.NOFOLLOW_LINKS)) {
                return ToolResult.error("Discovery path must be a directory, not a symbolic link");
            }
            Path root = requested.toRealPath();
            String operation = ToolArguments.string(arguments, "operation", "list");
            if (!List.of("list", "find", "search").contains(operation)) {
                throw new IllegalArgumentException("operation must be list, find or search");
            }
            int depth = ToolArguments.integer(arguments, "maxDepth", 5, 32);
            int results = ToolArguments.integer(arguments, "maxResults", 100, 500);
            PathMatcher matcher = root.getFileSystem().getPathMatcher(
                    "glob:" + ToolArguments.string(arguments, "glob", "*"));
            String query = operation.equals("search") ? ToolArguments.string(arguments, "query", null) : "";
            Scan scan = new Scan(root, operation, matcher, query,
                    ToolArguments.bool(arguments, "caseSensitive", false),
                    ToolArguments.bool(arguments, "includeHidden", false), results);
            Files.walkFileTree(root, java.util.Set.of(), operation.equals("list") ? 1 : depth, scan);
            String output = new ObjectMapper().writeValueAsString(Map.of(
                    "root", root.toString(), "results", scan.matches, "entriesVisited", scan.entries,
                    "skippedFiles", scan.skipped, "searchBytesRead", scan.bytesRead,
                    "incomplete", !scan.stopReason.isEmpty() || !scan.errors.isEmpty(),
                    "stopReason", scan.stopReason, "errors", scan.errors));
            return scan.errors.isEmpty() ? ToolResult.ok(output) : ToolResult.error(output);
        } catch (IllegalArgumentException e) {
            return ToolResult.error("Discovery arguments rejected: " + e.getMessage());
        }
    }

    private static final class Scan extends SimpleFileVisitor<Path> {
        final Path root;
        final String operation;
        final PathMatcher matcher;
        final Pattern query;
        final boolean includeHidden;
        final int maxResults;
        final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        final List<Map<String, Object>> matches = new ArrayList<>();
        final List<String> errors = new ArrayList<>();
        int entries;
        int skipped;
        int bytesRead;
        int outputChars;
        String stopReason = "";

        Scan(Path root, String operation, PathMatcher matcher, String query, boolean caseSensitive,
                boolean includeHidden, int maxResults) {
            this.root = root;
            this.operation = operation;
            this.matcher = matcher;
            this.query = Pattern.compile(query, Pattern.LITERAL
                    | (caseSensitive ? 0 : Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE));
            this.includeHidden = includeHidden;
            this.maxResults = maxResults;
        }

        private FileVisitResult stop(String reason) {
            stopReason = reason;
            return FileVisitResult.TERMINATE;
        }

        private FileVisitResult visit(Path file, BasicFileAttributes attrs) throws IOException {
            if (Thread.currentThread().isInterrupted()) {
                throw new java.io.InterruptedIOException("File discovery cancelled");
            }
            if (++entries > MAX_ENTRIES || System.nanoTime() > deadline) {
                return stop("Entry/time limit reached; narrow the root or glob");
            }
            if (attrs.isSymbolicLink() || (!includeHidden
                    && (file.getFileName().toString().startsWith(".") || Files.isHidden(file)))) {
                skipped++;
                return attrs.isDirectory() ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
            }
            if (!matcher.matches(file.getFileName()) && !matcher.matches(root.relativize(file))) {
                return FileVisitResult.CONTINUE;
            }
            if (operation.equals("search")) {
                if (!attrs.isRegularFile()) {
                    return FileVisitResult.CONTINUE;
                }
                if (attrs.size() > MAX_FILE_BYTES) {
                    skipped++;
                    return FileVisitResult.CONTINUE;
                }
                if (bytesRead + attrs.size() > MAX_SEARCH_BYTES) {
                    return stop("Search byte limit reached; narrow the root or glob");
                }
                byte[] bytes;
                try (var in = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
                    bytes = in.readNBytes(Math.min(MAX_FILE_BYTES + 1, MAX_SEARCH_BYTES - bytesRead + 1));
                }
                bytesRead += bytes.length;
                if (bytes.length > MAX_FILE_BYTES || bytesRead > MAX_SEARCH_BYTES) {
                    return stop("Search byte limit reached or file grew while reading");
                }
                String text;
                try {
                    text = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString();
                } catch (CharacterCodingException e) {
                    skipped++;
                    return FileVisitResult.CONTINUE;
                }
                if (text.indexOf('\0') >= 0) {
                    skipped++;
                    return FileVisitResult.CONTINUE;
                }
                String[] lines = text.split("\\R", -1);
                for (int line = 0; line < lines.length; line++) {
                    var match = query.matcher(lines[line]);
                    if (match.find()) {
                        int start = Math.max(0, match.start() - 80);
                        String snippet = lines[line].substring(start, Math.min(lines[line].length(), start + 300));
                        if (!add(Map.of("path", file.toString(), "line", line + 1, "snippet", snippet))) {
                            return FileVisitResult.TERMINATE;
                        }
                    }
                }
            } else if (attrs.isDirectory() || attrs.isRegularFile()) {
                if (!add(Map.of("path", file.toString(), "type", attrs.isDirectory() ? "directory" : "file",
                        "sizeBytes", attrs.size(), "modified", attrs.lastModifiedTime().toString()))) {
                    return FileVisitResult.TERMINATE;
                }
            }
            return FileVisitResult.CONTINUE;
        }

        private boolean add(Map<String, Object> match) throws IOException {
            int size = new ObjectMapper().writeValueAsString(match).length() + 1;
            if (outputChars + size > MAX_OUTPUT_CHARS) {
                stop("Output limit reached; narrow the search");
                return false;
            }
            matches.add(match);
            outputChars += size;
            if (matches.size() >= maxResults) {
                stop("Result limit reached; narrow the search or increase maxResults");
                return false;
            }
            return true;
        }

        @Override public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
            return dir.equals(root) ? FileVisitResult.CONTINUE : visit(dir, attrs);
        }

        @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
            try {
                return visit(file, attrs);
            } catch (java.io.InterruptedIOException e) {
                throw e;
            } catch (IOException e) {
                return visitFileFailed(file, e);
            }
        }

        @Override public FileVisitResult visitFileFailed(Path file, IOException error) {
            errors.add(file + ": " + error.getClass().getSimpleName());
            return errors.size() >= 5 ? stop("Too many filesystem errors") : FileVisitResult.CONTINUE;
        }

        @Override public FileVisitResult postVisitDirectory(Path dir, IOException error) {
            return error == null ? FileVisitResult.CONTINUE : visitFileFailed(dir, error);
        }
    }
}
