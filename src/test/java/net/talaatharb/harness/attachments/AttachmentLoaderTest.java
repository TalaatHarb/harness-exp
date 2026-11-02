package net.talaatharb.harness.attachments;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.ObjectMapper;

import net.talaatharb.harness.context.ContextSettings;
import net.talaatharb.harness.llm.Attachment;
import net.talaatharb.harness.llm.ChatMessage;
import net.talaatharb.harness.llm.Role;

class AttachmentLoaderTest {
    @TempDir
    Path dir;
    private final ContextSettings limits = new ContextSettings();

    @Test
    void loadsMarkdownAndStripsBom() throws IOException {
        Path file = dir.resolve("notes.md");
        Files.write(file, ("\uFEFF# Title\nbody").getBytes(StandardCharsets.UTF_8));

        Attachment a = AttachmentLoader.load(file, limits);

        assertEquals(Attachment.Kind.TEXT, a.kind());
        assertEquals("# Title\nbody", a.text());
        assertNull(a.data());
        assertFalse(a.truncated());
    }

    @Test
    void truncatesLongTextAndEnforcesSizeLimit() throws IOException {
        Path file = dir.resolve("big.txt");
        Files.writeString(file, "x".repeat(5_000));
        limits.maxAttachmentTextChars = 1_000;

        Attachment a = AttachmentLoader.load(file, limits);
        assertTrue(a.truncated());
        assertTrue(a.text().length() <= 1_100);

        limits.maxAttachmentMb = 1;
        Path huge = dir.resolve("huge.txt");
        Files.write(huge, new byte[2 * 1024 * 1024]);
        assertThrows(IllegalArgumentException.class, () -> AttachmentLoader.load(huge, limits));
    }

    @Test
    void rejectsUnsupportedAndEmptyFiles() throws IOException {
        Path exe = dir.resolve("tool.exe");
        Files.writeString(exe, "MZ");
        assertFalse(AttachmentLoader.supported(exe));
        assertThrows(IllegalArgumentException.class, () -> AttachmentLoader.load(exe, limits));

        Path empty = dir.resolve("empty.txt");
        Files.createFile(empty);
        assertThrows(IllegalArgumentException.class, () -> AttachmentLoader.load(empty, limits));
    }

    @Test
    void imagesAndAudioKeepTheirBytes() throws IOException {
        byte[] bytes = { (byte) 0x89, 'P', 'N', 'G', 1, 2, 3 };
        Path png = dir.resolve("pic.PNG");
        Files.write(png, bytes);
        Attachment image = AttachmentLoader.load(png, limits);
        assertEquals(Attachment.Kind.IMAGE, image.kind());
        assertArrayEquals(bytes, image.bytes());

        Path mp3 = dir.resolve("voice.mp3");
        Files.write(mp3, bytes);
        assertEquals(Attachment.Kind.AUDIO, AttachmentLoader.load(mp3, limits).kind());
    }

    @Test
    void extractsPdfText() throws IOException {
        Path pdf = dir.resolve("doc.pdf");
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage();
            doc.addPage(page);
            try (PDPageContentStream out = new PDPageContentStream(doc, page)) {
                out.beginText();
                out.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                out.newLineAtOffset(72, 700);
                out.showText("Quarterly revenue grew");
                out.endText();
            }
            doc.save(pdf.toFile());
        }

        Attachment a = AttachmentLoader.load(pdf, limits);

        assertEquals(Attachment.Kind.PDF, a.kind());
        assertTrue(a.text().contains("Quarterly revenue grew"), a.text());
    }

    @Test
    void pdfWithoutTextIsRejected() throws IOException {
        Path pdf = dir.resolve("scan.pdf");
        try (PDDocument doc = new PDDocument()) {
            doc.addPage(new PDPage());
            doc.save(pdf.toFile());
        }
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> AttachmentLoader.load(pdf, limits));
        assertTrue(e.getMessage().contains("no extractable text"));
    }

    @Test
    void messageContentForModelInlinesTextAndNotesOtherFiles() throws IOException {
        Path md = dir.resolve("a.md");
        Files.writeString(md, "hello file");
        Path png = dir.resolve("b.png");
        Files.write(png, new byte[] { 1, 2 });
        Path wav = dir.resolve("c.wav");
        Files.write(wav, new byte[] { 1, 2 });
        ChatMessage m = ChatMessage.user("summarize", List.of(AttachmentLoader.load(md, limits),
                AttachmentLoader.load(png, limits), AttachmentLoader.load(wav, limits)));

        String content = m.contentForModel();

        assertTrue(content.startsWith("summarize"));
        assertTrue(content.contains("hello file"));
        assertTrue(content.contains("a.md"));
        assertTrue(content.contains("b.png"));
        assertTrue(content.contains("c.wav"));
        assertEquals(1, m.images().size());
    }

    @Test
    void messagesRoundTripThroughJsonAndOldJsonStillLoads() throws IOException {
        ObjectMapper mapper = new ObjectMapper();
        Path md = dir.resolve("a.md");
        Files.writeString(md, "hello");
        ChatMessage m = ChatMessage.user("hi", List.of(AttachmentLoader.load(md, limits)));

        ChatMessage back = mapper.readValue(mapper.writeValueAsString(m), ChatMessage.class);
        assertEquals(m, back);

        ChatMessage old = mapper.readValue("{\"role\":\"USER\",\"content\":\"hi\"}", ChatMessage.class);
        assertEquals(Role.USER, old.role());
        assertTrue(old.attachments().isEmpty());
        assertFalse(mapper.writeValueAsString(old).contains("attachments"));
    }
}
