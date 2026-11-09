package net.talaatharb.harness.history;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.Base64;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.google.inject.name.Named;

/** Conversation-owned output files, isolated alongside the configured history database. */
@Singleton
public class ArtifactStore {
    public static final String ARTIFACT_PREFIX = "Downloadable artifact: ";
    public static final int MAX_BINARY_BYTES = 20 * 1024 * 1024;
    public record Artifact(String id, String name) {}

    private final Path root;
    private final Set<String> deleted = new HashSet<>();

    @Inject
    public ArtifactStore(@Named(ConversationStore.DB_PATH) Path historyPath) {
        root = historyPath.toAbsolutePath().normalize().resolveSibling(historyPath.getFileName() + ".artifacts");
    }

    private Path directory(String conversationId) {
        if (conversationId == null || conversationId.isBlank()) {
            throw new IllegalArgumentException("A conversation is required for downloadable files");
        }
        return root.resolve(UUID.nameUUIDFromBytes(conversationId.getBytes(StandardCharsets.UTF_8)).toString());
    }

    private static void validateName(String name) {
        if (name == null || name.isBlank() || name.equals(".") || name.equals("..")
                || name.matches(".*[\\\\/:*?\"<>|\\p{Cntrl}].*") || name.endsWith(".") || name.endsWith(" ")) {
            throw new IllegalArgumentException("Artifact path must be a plain filename, such as story.md");
        }
    }

    public synchronized Artifact write(String conversationId, String name, String content) throws IOException {
        return writeBytes(conversationId, name, content.getBytes(StandardCharsets.UTF_8));
    }

    public synchronized Artifact writeBinary(String conversationId, String name, byte[] content) throws IOException {
        if (content.length > MAX_BINARY_BYTES) {
            throw new IllegalArgumentException("Binary files are limited to 20 MiB");
        }
        return writeBytes(conversationId, name, content);
    }

    public static byte[] decodeBase64(String encoded) {
        if (encoded.length() > 4 * ((MAX_BINARY_BYTES + 2) / 3)) {
            throw new IllegalArgumentException("Binary files are limited to 20 MiB");
        }
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(encoded);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid Base64 data; provide standard Base64 without a data-URL prefix");
        }
        if (bytes.length > MAX_BINARY_BYTES) {
            throw new IllegalArgumentException("Binary files are limited to 20 MiB");
        }
        return bytes;
    }

    public static byte[] readBinary(Path source) throws IOException {
        if (!Files.isRegularFile(source)) {
            throw new IOException("Binary source is not a regular file: " + source);
        }
        try (var input = Files.newInputStream(source)) {
            byte[] bytes = input.readNBytes(MAX_BINARY_BYTES + 1);
            if (bytes.length > MAX_BINARY_BYTES) {
                throw new IOException("Binary files are limited to 20 MiB");
            }
            return bytes;
        }
    }

    private Artifact writeBytes(String conversationId, String name, byte[] content) throws IOException {
        validateName(name);
        Path conversation = directory(conversationId);
        if (deleted.contains(conversationId)) {
            throw new IOException("This conversation was deleted; its artifacts cannot be recreated");
        }
        checkDirectory(conversation);
        Files.createDirectories(conversation);
        Artifact artifact = new Artifact(UUID.randomUUID().toString(), name);
        Path folder = conversation.resolve(artifact.id());
        Files.createDirectory(folder);
        try {
            Files.write(folder.resolve(name), content, StandardOpenOption.CREATE_NEW);
        } catch (IOException e) {
            Files.deleteIfExists(folder.resolve(name));
            Files.deleteIfExists(folder);
            throw e;
        }
        return artifact;
    }

    private void checkDirectory(Path conversation) throws IOException {
        if (Files.isSymbolicLink(root) || Files.isSymbolicLink(conversation)) {
            throw new IOException("Artifact directories must not be symbolic links");
        }
    }

    public synchronized Path file(String conversationId, Artifact artifact) throws IOException {
        validateName(artifact.name());
        if (!UUID.fromString(artifact.id()).toString().equals(artifact.id())) {
            throw new IllegalArgumentException("Invalid artifact ID");
        }
        Path conversation = directory(conversationId);
        checkDirectory(conversation);
        Path folder = conversation.resolve(artifact.id());
        Path file = folder.resolve(artifact.name());
        if (Files.isSymbolicLink(folder) || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("The downloadable file is no longer available: " + artifact.name());
        }
        return file;
    }

    /** The newest managed file with this plain name in the conversation, e.g. for a Markdown image reference. */
    public synchronized java.util.Optional<Path> findByName(String conversationId, String name) throws IOException {
        try {
            validateName(name);
        } catch (IllegalArgumentException e) {
            return java.util.Optional.empty();
        }
        Path conversation = directory(conversationId);
        checkDirectory(conversation);
        if (!Files.isDirectory(conversation, LinkOption.NOFOLLOW_LINKS)) {
            return java.util.Optional.empty();
        }
        try (var folders = Files.list(conversation)) {
            return folders.filter(folder -> !Files.isSymbolicLink(folder))
                    .map(folder -> folder.resolve(name))
                    .filter(file -> Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS))
                    .max(Comparator.comparing(file -> {
                        try {
                            return Files.getLastModifiedTime(file, LinkOption.NOFOLLOW_LINKS);
                        } catch (IOException e) {
                            return java.nio.file.attribute.FileTime.fromMillis(0);
                        }
                    }));
        }
    }

    public synchronized void saveAs(String conversationId, Artifact artifact, Path destination) throws IOException {
        if (destination.toAbsolutePath().normalize().startsWith(root)) {
            throw new IOException("Choose a save location outside the managed artifacts folder");
        }
        Files.copy(file(conversationId, artifact), destination, StandardCopyOption.REPLACE_EXISTING);
    }

    public synchronized void deleteConversation(String conversationId) throws IOException {
        Path conversation = directory(conversationId);
        checkDirectory(conversation);
        // Block still-finishing runs from recreating artifacts after deletion.
        deleted.add(conversationId);
        if (Files.exists(conversation, LinkOption.NOFOLLOW_LINKS)) {
            try (var paths = Files.walk(conversation)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                    Files.delete(path);
                }
            }
        }
    }
}
