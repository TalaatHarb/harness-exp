package net.talaatharb.harness.speech;

import java.io.IOException;

/**
 * Local speech recognition. Implementations may download their model on first use ({@link #install}); the
 * default binding is {@link WhistleSpeechToText}.
 */
public interface SpeechToText {

    String displayName();

    /** {@code false} when this OS/CPU isn't supported at all. */
    boolean isSupported();

    /** Whether the engine/model files are already downloaded. */
    boolean isInstalled();

    /** Downloads whatever is missing. */
    void install(ProgressListener progress) throws IOException;

    /**
     * @param language a language code or {@link SpeechSettings#AUTO}
     */
    Transcription transcribe(AudioClip audio, String language, ProgressListener progress) throws IOException;

    record Transcription(String text, String language, long elapsedMillis) {
    }
}
