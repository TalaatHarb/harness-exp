package net.talaatharb.harness.reply;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public class ReplySettings {
    public enum Mode {
        /** Plain text replies. */
        TEXT,
        /** The model may embed {@code ```ui} JSON blocks that are rendered as live JavaFX components. */
        AGENTIC_UI
    }

    public Mode mode = Mode.AGENTIC_UI;
    /** Show tool calls/results inline in the chat as compact trace lines. */
    public boolean showToolTrace = true;
    /** Show the model's thinking text (for reasoning models) when available. */
    public boolean showThinking = false;
    public boolean showContext = false;
    /** Optional explicit response contract; zero/blank disables a constraint. */
    public int exactListItems = 0;
    public int maxBulletPoints = 0;
    public String allowedReplies = "";
    public int maxRepairAttempts = 1;
}
