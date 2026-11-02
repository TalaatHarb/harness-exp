package net.talaatharb.harness.attachments;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.talaatharb.harness.context.ContextSettings;
import net.talaatharb.harness.llm.Attachment;
import net.talaatharb.harness.llm.Attachment.Kind;

/** Turns files into {@link Attachment}s: markdown/text, PDF (text extracted), pictures and audio. */
public final class AttachmentLoader {
    private static final Logger LOG = LoggerFactory.getLogger(AttachmentLoader.class);

    private record Type(Kind kind, String mime) {
    }

    public static final String[] TEXT_EXTENSIONS = { "md", "markdown", "txt", "text", "log", "csv", "json", "yaml",
            "yml", "xml" };
    public static final String[] PDF_EXTENSIONS = { "pdf" };
    public static final String[] IMAGE_EXTENSIONS = { "png", "jpg", "jpeg", "gif", "bmp", "webp" };
    public static final String[] AUDIO_EXTENSIONS = { "mp3", "wav", "m4a", "aac", "aif", "aiff", "ogg", "flac" };

    private static final Map<String, String> MIME = Map.ofEntries(
            Map.entry("md", "text/markdown"), Map.entry("markdown", "text/markdown"),
            Map.entry("txt", "text/plain"), Map.entry("text", "text/plain"), Map.entry("log", "text/plain"),
            Map.entry("csv", "text/csv"), Map.entry("json", "application/json"),
            Map.entry("yaml", "application/yaml"), Map.entry("yml", "application/yaml"),
            Map.entry("xml", "application/xml"), Map.entry("pdf", "application/pdf"),
            Map.entry("png", "image/png"), Map.entry("jpg", "image/jpeg"), Map.entry("jpeg", "image/jpeg"),
            Map.entry("gif", "image/gif"), Map.entry("bmp", "image/bmp"), Map.entry("webp", "image/webp"),
            Map.entry("mp3", "audio/mpeg"), Map.entry("wav", "audio/wav"), Map.entry("m4a", "audio/mp4"),
            Map.entry("aac", "audio/aac"), Map.entry("aif", "audio/aiff"), Map.entry("aiff", "audio/aiff"),
            Map.entry("ogg", "audio/ogg"), Map.entry("flac", "audio/flac"));

    private AttachmentLoader() {
    }

    public static boolean supported(Path file) {
        return type(file).isPresent();
    }

    /**
     * @throws IllegalArgumentException for unsupported, empty or too large files
     * @throws IOException              if the file can't be read or parsed
     */
    public static Attachment load(Path file, ContextSettings limits) throws IOException {
        String name = file.getFileName() == null ? "attachment" : file.getFileName().toString();
        Type type = type(file).orElseThrow(() -> new IllegalArgumentException(
                "Unsupported file type: " + name + " (use markdown/text, PDF, pictures or audio)"));
        long size = Files.size(file);
        long max = (long) Math.max(1, limits.maxAttachmentMb) * 1024 * 1024;
        if (size > max) {
            throw new IllegalArgumentException(name + " is " + Attachment.formatSize(size) + "; the limit is "
                    + limits.maxAttachmentMb + " MB");
        }
        if (size == 0) {
            throw new IllegalArgumentException(name + " is empty");
        }
        byte[] bytes = Files.readAllBytes(file);
        Attachment attachment = switch (type.kind()) {
            case TEXT -> text(name, type, size, decode(bytes), limits);
            case PDF -> text(name, type, size, pdfText(name, bytes), limits);
            case IMAGE, AUDIO -> new Attachment(name, type.mime(), type.kind(), size, null,
                    Base64.getEncoder().encodeToString(bytes), false);
        };
        LOG.info("Attached {} ({}, {})", name, type.kind(), Attachment.formatSize(size));
        return attachment;
    }

    private static Optional<Type> type(Path file) {
        String name = file.getFileName() == null ? "" : file.getFileName().toString();
        int dot = name.lastIndexOf('.');
        if (dot < 0) {
            return Optional.empty();
        }
        String ext = name.substring(dot + 1).toLowerCase(Locale.ROOT);
        String mime = MIME.get(ext);
        if (mime == null) {
            return Optional.empty();
        }
        Kind kind = ext.equals("pdf") ? Kind.PDF
                : mime.startsWith("image/") ? Kind.IMAGE : mime.startsWith("audio/") ? Kind.AUDIO : Kind.TEXT;
        return Optional.of(new Type(kind, mime));
    }

    private static Attachment text(String name, Type type, long size, String text, ContextSettings limits) {
        int max = Math.max(1, limits.maxAttachmentTextChars);
        boolean truncated = text.length() > max;
        return new Attachment(name, type.mime(), type.kind(), size, truncated ? text.substring(0, max) : text, null,
                truncated);
    }

    /** UTF-8 (BOM stripped), falling back to ISO-8859-1 for legacy files. */
    static String decode(byte[] bytes) {
        int offset = bytes.length >= 3 && (bytes[0] & 0xFF) == 0xEF && (bytes[1] & 0xFF) == 0xBB
                && (bytes[2] & 0xFF) == 0xBF ? 3 : 0;
        ByteBuffer buffer = ByteBuffer.wrap(bytes, offset, bytes.length - offset);
        try {
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(buffer).toString();
        } catch (CharacterCodingException e) {
            return new String(bytes, offset, bytes.length - offset, StandardCharsets.ISO_8859_1);
        }
    }

    private static String pdfText(String name, byte[] bytes) throws IOException {
        String text;
        try (PDDocument document = Loader.loadPDF(bytes)) {
            text = new PDFTextStripper().getText(document).strip();
        } catch (IOException e) {
            throw new IOException("Could not read PDF " + name + ": " + e.getMessage(), e);
        }
        if (text.isEmpty()) {
            throw new IllegalArgumentException(name + " has no extractable text (scanned PDF?)");
        }
        return text;
    }
}
