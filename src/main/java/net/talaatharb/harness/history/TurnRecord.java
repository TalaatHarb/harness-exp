package net.talaatharb.harness.history;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import net.talaatharb.harness.agent.AgentRunResult;

/** One user request and the outcome of the agent run that answered it. */
@JsonIgnoreProperties(ignoreUnknown = true)
public class TurnRecord {
    public String userText;
    /** Index in {@link ConversationRecord#messages} where this turn starts; used to rewind for regenerate. */
    public int messageStart;
    public AgentRunResult.Status status;
    public String reply;
    public String reason;
    /** Redacted run timeline (absent for turns saved before timelines existed). */
    public net.talaatharb.harness.trace.RunTimeline timeline;

    public TurnRecord() {
    }

    public TurnRecord(String userText, int messageStart) {
        this.userText = userText;
        this.messageStart = messageStart;
    }

    public void complete(AgentRunResult result) {
        status = result.status();
        reply = result.reply();
        reason = result.reason();
    }

    /** Result to re-render; turns saved without an outcome (e.g. the app closed mid-run) show as cancelled. */
    public AgentRunResult toResult() {
        if (status == null) {
            return new AgentRunResult(AgentRunResult.Status.CANCELLED, null, "Run did not finish", 0, 0);
        }
        return new AgentRunResult(status, reply, reason, 0, 0);
    }
}
