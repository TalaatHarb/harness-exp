package net.talaatharb.harness.speech;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Turns an audio attachment into PCM for transcription. WAV/AIFF/AU are decoded in Java; other formats (mp3, m4a,
 * ogg, flac, aac) need {@code ffmpeg} on the PATH (or {@code -Dharness.ffmpeg=<path>}).
 */
public final class AudioDecoder {
    public static final String FFMPEG_PROPERTY = "harness.ffmpeg";
    private static final Logger LOG = LoggerFactory.getLogger(AudioDecoder.class);
    private static final List<String> NATIVE = List.of("wav", "wave", "aif", "aiff", "au");

    private AudioDecoder() {
    }

    public static boolean decodesNatively(String fileName) {
        return NATIVE.contains(extension(fileName));
    }

    public static AudioClip decode(byte[] data, String fileName) throws IOException {
        String ext = extension(fileName);
        Path input = Files.createTempFile("harness-audio-", "." + (ext.isEmpty() ? "audio" : ext));
        Path wav = null;
        try {
            Files.write(input, data);
            if (decodesNatively(fileName)) {
                return WavIO.read(input);
            }
            wav = Files.createTempFile("harness-audio-", ".wav");
            convertWithFfmpeg(input, wav);
            return WavIO.read(wav);
        } finally {
            Files.deleteIfExists(input);
            if (wav != null) {
                Files.deleteIfExists(wav);
            }
        }
    }

    private static void convertWithFfmpeg(Path input, Path wav) throws IOException {
        String ffmpeg = System.getProperty(FFMPEG_PROPERTY, "ffmpeg");
        List<String> command = List.of(ffmpeg, "-nostdin", "-hide_banner", "-loglevel", "error", "-y", "-i",
                input.toString(), "-ac", "1", "-ar", String.valueOf(WhistleSpeechToText.SAMPLE_RATE), "-f", "wav",
                wav.toString());
        Process process;
        try {
            process = new ProcessBuilder(command).redirectErrorStream(true).start();
        } catch (IOException e) {
            throw new IOException("Only WAV/AIFF audio can be transcribed without ffmpeg. Install ffmpeg (on the PATH "
                    + "or -D" + FFMPEG_PROPERTY + "=<path>) to transcribe " + extension(input.toString()) + " files.",
                    e);
        }
        try {
            String output = new String(process.getInputStream().readAllBytes());
            if (!process.waitFor(120, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new IOException("ffmpeg timed out");
            }
            if (process.exitValue() != 0) {
                LOG.warn("ffmpeg failed: {}", output.strip());
                throw new IOException("ffmpeg could not decode the file: " + output.strip());
            }
        } catch (InterruptedException e) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while converting audio", e);
        }
    }

    static String extension(String name) {
        int dot = name == null ? -1 : name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }
}
