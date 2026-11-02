package net.talaatharb.harness.speech;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** Speech-to-text options (Harness → Speech). */
@JsonIgnoreProperties(ignoreUnknown = true)
public class SpeechSettings {
    public static final String AUTO = "auto";
    /** Language codes Whistle supports, plus {@link #AUTO}. */
    public static final List<String> LANGUAGES = List.of(AUTO, "en", "de", "fr", "es", "it", "nl", "pl");
    public static final int MAX_RECORDING_SECONDS_LIMIT = 1_800;

    /** Shows the 🎤 button and enables the dictation shortcut. */
    public boolean dictationEnabled = true;
    /** Sends the message as soon as the dictated text is in the input box (talk instead of typing). */
    public boolean sendAfterDictation = false;
    /** Transcribes audio attachments so the model gets their words instead of just a note. */
    public boolean transcribeAudioAttachments = true;
    /** Spoken language, or {@link #AUTO} to let the model detect it. */
    public String language = AUTO;
    /** Recordings stop automatically after this many seconds. */
    public int maxRecordingSeconds = 120;
}
