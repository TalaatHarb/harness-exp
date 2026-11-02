package net.talaatharb.harness.ui;

import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Supplier;

import javax.sound.sampled.LineUnavailableException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.beans.property.ReadOnlyBooleanProperty;
import javafx.beans.property.ReadOnlyBooleanWrapper;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.Region;
import javafx.util.Duration;
import net.talaatharb.harness.llm.Attachment;
import net.talaatharb.harness.speech.AudioClip;
import net.talaatharb.harness.speech.AudioDecoder;
import net.talaatharb.harness.speech.AudioRecorder;
import net.talaatharb.harness.speech.ProgressListener;
import net.talaatharb.harness.speech.SpeechSettings;
import net.talaatharb.harness.speech.SpeechToText;

/**
 * "Talk instead of typing": the composer's 🎤 button and {@link #SHORTCUT} record from the microphone, transcribe
 * locally and put the words in the input box (or send them). Also transcribes audio attachments.
 */
final class VoiceInput {
    static final KeyCombination SHORTCUT = new KeyCodeCombination(KeyCode.M, KeyCombination.CONTROL_DOWN);
    private static final Logger LOG = LoggerFactory.getLogger(VoiceInput.class);

    private final SpeechToText speech;
    private final Supplier<SpeechSettings> settings;
    private final Button micButton;
    private final Label status;
    private final TextArea input;
    private final Runnable send;
    private final Consumer<String> showError;
    private final AudioRecorder recorder = new AudioRecorder();
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "speech-to-text");
        t.setDaemon(true);
        return t;
    });
    private final ReadOnlyBooleanWrapper busy = new ReadOnlyBooleanWrapper();
    private final Timeline ticker;
    private long startedAt;
    private AtomicBoolean cancel = new AtomicBoolean();

    VoiceInput(SpeechToText speech, Supplier<SpeechSettings> settings, Button micButton, Label status,
            TextArea input, Runnable send, Consumer<String> showError) {
        this.speech = speech;
        this.settings = settings;
        this.micButton = micButton;
        this.status = status;
        this.input = input;
        this.send = send;
        this.showError = showError;
        this.ticker = new Timeline(new KeyFrame(Duration.millis(200), e -> tick()));
        ticker.setCycleCount(Timeline.INDEFINITE);
        status.managedProperty().bind(status.visibleProperty());
        setStatus(null);
        micButton.setOnAction(e -> toggle());
        refresh();
    }

    /** Ctrl+M toggles recording anywhere in the window; Esc cancels a recording or transcription. */
    void installShortcuts(Region root) {
        root.addEventFilter(KeyEvent.KEY_PRESSED, e -> {
            if (SHORTCUT.match(e)) {
                e.consume();
                if (!micButton.isDisabled()) {
                    toggle();
                }
            } else if (e.getCode() == KeyCode.ESCAPE && (recorder.isRecording() || busy.get())) {
                e.consume();
                cancel();
            }
        });
    }

    /** True while a dictation is being transcribed (sending is held back). */
    ReadOnlyBooleanProperty busyProperty() {
        return busy.getReadOnlyProperty();
    }

    /** Shows/hides the mic per settings. */
    void refresh() {
        boolean enabled = settings.get().dictationEnabled && speech.isSupported();
        micButton.setVisible(enabled);
        micButton.setManaged(enabled);
    }

    void toggle() {
        if (recorder.isRecording()) {
            stopAndTranscribe();
        } else if (!busy.get()) {
            start();
        }
    }

    private void start() {
        try {
            recorder.start();
        } catch (LineUnavailableException | RuntimeException e) {
            LOG.error("Could not start recording", e);
            showError.accept("Could not open the microphone: " + e.getMessage());
            return;
        }
        startedAt = System.nanoTime();
        micButton.setText("⏹");
        micButton.getStyleClass().add("recording");
        ticker.play();
        tick();
        LOG.info("Recording started");
    }

    private void tick() {
        if (!recorder.isRecording()) {
            return;
        }
        double seconds = (System.nanoTime() - startedAt) / 1e9;
        int max = settings.get().maxRecordingSeconds;
        if (seconds >= max) {
            stopAndTranscribe();
            return;
        }
        int bars = (int) Math.round(Math.min(1, recorder.level() * 2) * 10);
        setStatus(String.format("● Recording %d:%02d  %s%s   %s to stop · Esc to cancel", (int) seconds / 60,
                (int) seconds % 60, "▮".repeat(bars), "▯".repeat(10 - bars), SHORTCUT.getDisplayText()));
    }

    private void stopRecorder() {
        ticker.stop();
        micButton.setText("🎤");
        micButton.getStyleClass().remove("recording");
    }

    private void stopAndTranscribe() {
        AudioClip clip = recorder.stop();
        stopRecorder();
        LOG.info("Recording stopped ({} s)", String.format("%.1f", clip.durationSeconds()));
        if (clip.durationSeconds() < 0.3) {
            setStatus(null);
            return;
        }
        busy.set(true);
        micButton.setDisable(true);
        cancel = new AtomicBoolean();
        AtomicBoolean flag = cancel;
        String language = settings.get().language;
        setStatus("Transcribing…  (Esc to cancel)");
        CompletableFuture.supplyAsync(() -> {
            try {
                return speech.transcribe(clip, language, listener(flag));
            } catch (java.io.IOException e) {
                throw new CompletionException(e);
            }
        }, worker).whenComplete((result, error) -> Platform.runLater(() -> {
            busy.set(false);
            micButton.setDisable(false);
            setStatus(null);
            if (error != null) {
                Throwable cause = error instanceof CompletionException && error.getCause() != null
                        ? error.getCause() : error;
                if (!(cause instanceof CancellationException)) {
                    LOG.error("Transcription failed", cause);
                    showError.accept("Transcription failed: " + cause.getMessage());
                }
                return;
            }
            insert(result.text());
            if (settings.get().sendAfterDictation && !result.text().isBlank()) {
                send.run();
            }
        }));
    }

    private void insert(String text) {
        if (text == null || text.isBlank()) {
            setStatus("Didn't catch any speech");
            return;
        }
        String current = input.getText() == null ? "" : input.getText();
        int caret = Math.min(input.getCaretPosition(), current.length());
        String before = current.substring(0, caret);
        String after = current.substring(caret);
        String piece = (before.isEmpty() || before.endsWith(" ") || before.endsWith("\n") ? "" : " ") + text.strip()
                + (after.isEmpty() || after.startsWith(" ") ? "" : " ");
        input.insertText(caret, piece);
        input.positionCaret(caret + piece.length());
        input.requestFocus();
    }

    void cancel() {
        if (recorder.isRecording()) {
            recorder.stop();
            stopRecorder();
            setStatus(null);
            LOG.info("Recording cancelled");
        }
        cancel.set(true);
    }

    /**
     * Adds a transcript to an audio attachment when enabled. Runs on the caller's (background) thread; failures are
     * logged and the attachment is returned unchanged with the problem reported.
     */
    Attachment transcribeAttachment(Attachment a, int maxChars, Consumer<String> problem) {
        SpeechSettings s = settings.get();
        if (a.kind() != Attachment.Kind.AUDIO || !s.transcribeAudioAttachments || !speech.isSupported()) {
            return a;
        }
        try {
            AudioClip clip = AudioDecoder.decode(a.bytes(), a.name());
            Platform.runLater(() -> setStatus("Transcribing " + a.name() + "…"));
            String text = CompletableFuture.supplyAsync(() -> {
                try {
                    return speech.transcribe(clip, s.language, listener(new AtomicBoolean())).text();
                } catch (java.io.IOException e) {
                    throw new CompletionException(e);
                }
            }, worker).join();
            return a.withText(text, maxChars);
        } catch (Exception e) {
            Throwable cause = e instanceof CompletionException && e.getCause() != null ? e.getCause() : e;
            LOG.warn("Could not transcribe {}", a.name(), cause);
            problem.accept(a.name() + ": attached without a transcript (" + cause.getMessage() + ")");
            return a;
        } finally {
            Platform.runLater(() -> {
                if (!busy.get() && !recorder.isRecording()) {
                    setStatus(null);
                }
            });
        }
    }

    private ProgressListener listener(AtomicBoolean flag) {
        return new ProgressListener() {
            @Override
            public void update(String message, double fraction) {
                String text = fraction >= 0 && fraction < 1 ? message + " " + Math.round(fraction * 100) + "%"
                        : message;
                Platform.runLater(() -> setStatus(text));
            }

            @Override
            public boolean isCancelled() {
                return flag.get();
            }
        };
    }

    private void setStatus(String text) {
        status.setText(text == null ? "" : text);
        status.setVisible(text != null && !text.isEmpty());
    }

    void shutdown() {
        if (recorder.isRecording()) {
            recorder.stop();
        }
        cancel.set(true);
        ticker.stop();
        worker.shutdownNow();
    }
}
