package net.talaatharb.harness.speech;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.sun.net.httpserver.HttpServer;

import net.talaatharb.harness.llm.Attachment;
import net.talaatharb.harness.llm.ChatMessage;

class SpeechTest {
    private static final CactusPlatform WINDOWS = CactusPlatform.detect("Windows 11", "amd64");

    @TempDir
    Path dir;

    @Test
    void detectsEnginePlatforms() {
        assertEquals("releases/4.1.0/windows-x86_64/cactus.exe", WINDOWS.remotePath());
        assertEquals("releases/4.1.0/macos-arm64/cactus", CactusPlatform.detect("Mac OS X", "aarch64").remotePath());
        assertEquals("releases/4.1.0/linux-armv7/cactus", CactusPlatform.detect("Linux", "armv7l").remotePath());
        assertEquals("releases/4.1.0/linux-mipsel/cactus", CactusPlatform.detect("Linux", "mipsel").remotePath());
        assertThrows(UnsupportedOperationException.class, () -> CactusPlatform.detect("Mac OS X", "x86_64"));
        assertThrows(UnsupportedOperationException.class, () -> CactusPlatform.detect("Windows 11", "x86"));
    }

    @Test
    void enginePinsTheCactusReleaseRevision() {
        ModelDownloader downloader = new ModelDownloader("https://hf.example/");
        WhistleSpeechToText whistle = new WhistleSpeechToText(dir, downloader, WINDOWS);
        assertEquals(dir.resolve("whistle/cactus-4.1.0/windows-x86_64/cactus.exe"), whistle.engineFile());
        assertEquals("https://hf.example/Cactus-Compute/cactus/resolve/v4.1.0/releases/4.1.0/windows-x86_64/cactus.exe",
                downloader.url(new ModelDownloader.RemoteFile(WhistleSpeechToText.ENGINE_REPO, CactusPlatform.REVISION,
                        WINDOWS.remotePath(), whistle.engineFile(), WINDOWS.sha256())));
    }

    @Test
    void wavRoundTripAndResample() throws IOException {
        float[] samples = new float[48_000];
        for (int i = 0; i < samples.length; i++) {
            samples[i] = (float) (0.5 * Math.sin(2 * Math.PI * 440 * i / 48_000.0));
        }
        AudioClip clip = new AudioClip(samples, 48_000);
        Path wav = dir.resolve("tone.wav");
        WavIO.write(clip, wav);
        AudioClip read = WavIO.read(wav);
        assertEquals(48_000, read.sampleRate());
        assertEquals(samples.length, read.samples().length);
        assertEquals(samples[100], read.samples()[100], 1e-3);

        AudioClip down = read.atSampleRate(16_000);
        assertEquals(16_000, down.sampleRate());
        assertEquals(1.0, down.durationSeconds(), 0.01);
    }

    @Test
    void decodesWavAttachmentsWithoutFfmpeg() throws IOException {
        byte[] wav = WavIO.toWavBytes(new AudioClip(new float[8_000], 8_000));
        AudioClip clip = AudioDecoder.decode(wav, "note.WAV");
        assertEquals(8_000, clip.sampleRate());
        assertEquals(1.0, clip.durationSeconds(), 0.01);
        assertTrue(AudioDecoder.decodesNatively("a.aiff"));
        assertFalse(AudioDecoder.decodesNatively("a.mp3"));
    }

    @Test
    void mp3WithoutFfmpegExplainsWhatToDo() {
        String old = System.getProperty(AudioDecoder.FFMPEG_PROPERTY);
        System.setProperty(AudioDecoder.FFMPEG_PROPERTY, dir.resolve("no-such-ffmpeg").toString());
        try {
            IOException e = assertThrows(IOException.class, () -> AudioDecoder.decode(new byte[10], "talk.mp3"));
            assertTrue(e.getMessage().contains("ffmpeg"), e.getMessage());
        } finally {
            if (old == null) {
                System.clearProperty(AudioDecoder.FFMPEG_PROPERTY);
            } else {
                System.setProperty(AudioDecoder.FFMPEG_PROPERTY, old);
            }
        }
    }

    @Test
    void buildsWhistleCommandAndParsesOutput() throws IOException {
        WhistleSpeechToText whistle = new WhistleSpeechToText(dir, new ModelDownloader("http://unused"), WINDOWS);
        Path wav = dir.resolve("in.wav");
        List<String> auto = whistle.command(wav, SpeechSettings.AUTO);
        assertEquals(List.of(dir.resolve("whistle").resolve("cactus-4.1.0").resolve("windows-x86_64")
                .resolve("cactus.exe").toString(),
                "--model", dir.resolve("whistle").resolve("whistle.cact").toString(), "--audio", wav.toString()),
                auto);
        assertEquals(List.of("--audio-language", "de"), whistle.command(wav, "de").subList(5, 7));
        assertFalse(whistle.isInstalled());

        var result = whistle.parse("loading model...\nready\n{\"text\": \" Hello world. \", \"language\": \"en\"}\n",
                42);
        assertEquals("Hello world.", result.text());
        assertEquals("en", result.language());
        assertThrows(IOException.class, () -> whistle.parse("Segmentation fault", 1));
    }

    @Test
    void unsupportedPlatformIsReportedNotThrown() {
        WhistleSpeechToText whistle = new WhistleSpeechToText(dir, new ModelDownloader("http://unused"), null);
        assertFalse(whistle.isSupported());
        assertFalse(whistle.isInstalled());
    }

    @Test
    void downloadsMissingFilesOnlyOnce() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        List<String> requested = new ArrayList<>();
        server.createContext("/", ex -> {
            synchronized (requested) {
                requested.add(ex.getRequestURI().getPath());
            }
            byte[] body = ex.getRequestURI().getPath().endsWith("missing") ? new byte[0]
                    : ex.getRequestURI().getPath().getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(body.length == 0 ? 404 : 200, body.length == 0 ? -1 : body.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(body);
            }
        });
        server.start();
        try {
            ModelDownloader downloader = new ModelDownloader("http://127.0.0.1:" + server.getAddress().getPort() + "/");
            Path engine = dir.resolve("whistle/cactus-4.1.0/windows-x86_64/cactus.exe");
            String enginePath = "/Org/cactus/resolve/v4.1.0/releases/4.1.0/windows-x86_64/cactus.exe";
            String engineSha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(enginePath.getBytes(StandardCharsets.UTF_8)));
            var files = List.of(
                    new ModelDownloader.RemoteFile("Org/cactus", "v4.1.0", "releases/4.1.0/windows-x86_64/cactus.exe",
                            engine, engineSha.toUpperCase()),
                    new ModelDownloader.RemoteFile("Org/whistle", "whistle.cact", dir.resolve("whistle/whistle.cact")));
            List<Double> fractions = new ArrayList<>();
            downloader.ensure(files, (m, f) -> fractions.add(f));
            assertTrue(ModelDownloader.allPresent(files));
            assertEquals(enginePath, Files.readString(engine));
            assertEquals("/Org/whistle/resolve/main/whistle.cact", Files.readString(dir.resolve("whistle/whistle.cact")));
            assertFalse(fractions.isEmpty());
            downloader.ensure(files, ProgressListener.NONE);
            assertEquals(2, requested.size());

            Path tampered = dir.resolve("tampered");
            IOException mismatch = assertThrows(IOException.class, () -> downloader.ensure(
                    List.of(new ModelDownloader.RemoteFile("Org/x", "v1", "tampered", tampered, "00".repeat(32))),
                    ProgressListener.NONE));
            assertTrue(mismatch.getMessage().contains("Checksum mismatch"), mismatch.getMessage());
            assertFalse(Files.exists(tampered));
            assertFalse(Files.exists(dir.resolve("tampered.part")));

            Path target = dir.resolve("missing");
            assertThrows(IOException.class, () -> downloader.ensure(
                    List.of(new ModelDownloader.RemoteFile("Org/x", "missing", target)), ProgressListener.NONE));
            assertFalse(Files.exists(target));
            assertFalse(Files.exists(dir.resolve("missing.part")));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void audioTranscriptIsSentToTheModel() {
        String data = Base64.getEncoder().encodeToString(new byte[] {1, 2});
        Attachment audio = new Attachment("memo.wav", "audio/wav", Attachment.Kind.AUDIO, 2, null, data, false);
        assertTrue(ChatMessage.user("hi", List.of(audio)).contentForModel().contains("can't be sent"));

        Attachment transcribed = audio.withText("Buy milk and eggs", 8);
        assertEquals("Buy milk", transcribed.text());
        assertTrue(transcribed.truncated());
        assertArrayEquals(new byte[] {1, 2}, transcribed.bytes());
        String content = ChatMessage.user("hi", List.of(transcribed)).contentForModel();
        assertTrue(content.contains("speech transcript"), content);
        assertTrue(content.contains("Buy milk"), content);
        assertNull(audio.withText(null, 5).text());
    }
}
