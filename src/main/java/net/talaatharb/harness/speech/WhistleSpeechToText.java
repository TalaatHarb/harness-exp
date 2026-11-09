package net.talaatharb.harness.speech;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.google.inject.name.Named;

/**
 * Speech to text with Cactus Compute's <a href="https://huggingface.co/Cactus-Compute/whistle">Whistle</a>
 * (16.9 MB {@code whistle.cact}, Apache 2.0), as in <a href="https://github.com/TalaatHarb/stt-tts">stt-tts</a>.
 * <p>
 * The model runs on the prebuilt {@code cactus} runtime executable for the current OS/CPU (pinned release
 * {@value CactusPlatform#RELEASE} of <a href="https://huggingface.co/Cactus-Compute/cactus">Cactus-Compute/cactus</a>,
 * checksum-verified), called as a subprocess. Runtime and model are downloaded from Hugging Face on first use into
 * {@code <models>/whistle}.
 */
@Singleton
public class WhistleSpeechToText implements SpeechToText {
    public static final String MODELS_DIR = "modelsDir";
    static final String ENGINE_REPO = "Cactus-Compute/cactus";
    static final String MODEL_REPO = "Cactus-Compute/whistle";
    static final int SAMPLE_RATE = 16_000;
    private static final Logger LOG = LoggerFactory.getLogger(WhistleSpeechToText.class);

    private final ModelDownloader downloader;
    private final Path directory;
    private final CactusPlatform platform;
    private final ObjectMapper json = new ObjectMapper();

    @Inject
    public WhistleSpeechToText(@Named(MODELS_DIR) Path modelsDir, ModelDownloader downloader) {
        this(modelsDir, downloader, detect());
    }

    WhistleSpeechToText(Path modelsDir, ModelDownloader downloader, CactusPlatform platform) {
        this.downloader = downloader;
        this.directory = modelsDir.resolve("whistle");
        this.platform = platform;
    }

    private static CactusPlatform detect() {
        try {
            return CactusPlatform.current();
        } catch (UnsupportedOperationException e) {
            LOG.warn(e.getMessage());
            return null;
        }
    }

    @Override
    public String displayName() {
        return "Whistle (Cactus Compute, on-device)";
    }

    @Override
    public boolean isSupported() {
        return platform != null;
    }

    public Path directory() {
        return directory;
    }

    Path modelFile() {
        return directory.resolve("whistle.cact");
    }

    Path engineFile() {
        return directory.resolve("cactus-" + CactusPlatform.RELEASE).resolve(requirePlatform().folder())
                .resolve(requirePlatform().executable());
    }

    private CactusPlatform requirePlatform() {
        if (platform == null) {
            throw new IllegalStateException("Speech to text (Whistle) isn't available for "
                    + System.getProperty("os.name") + " / " + System.getProperty("os.arch"));
        }
        return platform;
    }

    private List<ModelDownloader.RemoteFile> files() {
        CactusPlatform engine = requirePlatform();
        return List.of(new ModelDownloader.RemoteFile(ENGINE_REPO, CactusPlatform.REVISION, engine.remotePath(),
                engineFile(), engine.sha256()),
                new ModelDownloader.RemoteFile(MODEL_REPO, "whistle.cact", modelFile()));
    }

    @Override
    public boolean isInstalled() {
        return platform != null && ModelDownloader.allPresent(files());
    }

    @Override
    public synchronized void install(ProgressListener progress) throws IOException {
        downloader.ensure(files(), progress);
        Path engine = engineFile();
        if (!engine.toFile().canExecute() && !engine.toFile().setExecutable(true)) {
            throw new IOException("Could not mark the Cactus runtime as executable: " + engine);
        }
    }

    @Override
    public Transcription transcribe(AudioClip audio, String language, ProgressListener progress) throws IOException {
        if (audio.isEmpty()) {
            throw new IllegalArgumentException("The recording is empty");
        }
        long started = System.nanoTime();
        if (!isInstalled()) {
            install(progress.slice(0, 0.3));
        }
        progress.update("Transcribing…", -1);
        Path wav = Files.createTempFile("whistle-", ".wav");
        try {
            WavIO.write(audio.atSampleRate(SAMPLE_RATE), wav);
            String output = run(command(wav, language), progress);
            Transcription result = parse(output, (System.nanoTime() - started) / 1_000_000);
            LOG.info("Transcribed {}s of audio in {} ms ({} chars, language {})",
                    String.format("%.1f", audio.durationSeconds()), result.elapsedMillis(), result.text().length(),
                    result.language());
            return result;
        } finally {
            Files.deleteIfExists(wav);
        }
    }

    List<String> command(Path wav, String language) {
        List<String> command = new ArrayList<>(List.of(engineFile().toString(), "--model", modelFile().toString(),
                "--audio", wav.toString()));
        if (language != null && !language.isBlank() && !SpeechSettings.AUTO.equals(language)) {
            command.add("--audio-language");
            command.add(language);
        }
        return command;
    }

    private String run(List<String> command, ProgressListener progress) throws IOException {
        Process process = new ProcessBuilder(command).directory(directory.toFile()).start();
        CompletableFuture<String> stdout = readAsync(process.getInputStream());
        CompletableFuture<String> stderr = readAsync(process.getErrorStream());
        try {
            while (!process.waitFor(100, TimeUnit.MILLISECONDS)) {
                if (progress.isCancelled() || Thread.currentThread().isInterrupted()) {
                    process.destroyForcibly();
                    throw new CancellationException("Cancelled");
                }
            }
            if (process.exitValue() != 0) {
                throw new IOException("Whistle engine exited with code " + process.exitValue() + ": "
                        + stderr.join().trim());
            }
            return stdout.join();
        } catch (InterruptedException e) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new CancellationException("Interrupted");
        }
    }

    private static CompletableFuture<String> readAsync(InputStream stream) {
        return CompletableFuture.supplyAsync(() -> {
            try (InputStream in = stream) {
                return new String(in.readAllBytes(), StandardCharsets.UTF_8);
            } catch (IOException e) {
                return "";
            }
        });
    }

    /** The engine prints logs followed by a final JSON line: {@code {"text": "...", "language": "en", ...}}. */
    Transcription parse(String output, long elapsedMillis) throws IOException {
        String trimmed = output.trim();
        int start = trimmed.lastIndexOf("\n{");
        String line = start >= 0 ? trimmed.substring(start + 1) : trimmed;
        JsonNode root;
        try {
            root = json.readTree(line);
        } catch (IOException e) {
            throw new IOException("Unexpected Whistle engine output: " + abbreviate(trimmed), e);
        }
        if (root == null || !root.has("text")) {
            throw new IOException("Unexpected Whistle engine output: " + abbreviate(trimmed));
        }
        return new Transcription(root.path("text").asText().trim(), root.path("language").asText(null),
                elapsedMillis);
    }

    private static String abbreviate(String s) {
        return s.length() > 300 ? s.substring(0, 300) + "…" : s;
    }
}
