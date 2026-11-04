package net.talaatharb.harness.context;

import java.util.HashSet;
import java.util.Set;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public class ContextSettings {
    /** Number of most recent conversation messages kept in the context window. */
    public int maxHistoryMessages = 20;
    /** Static text always appended to the system context (project notes, user profile...). */
    public String pinnedContext = "";
    /** Ids of {@link ContextContributor}s the user switched off. */
    public Set<String> disabledContributors = new HashSet<>();
    /** Most files per message. */
    public int maxAttachments = 8;
    /** Largest accepted attachment file. */
    public int maxAttachmentMb = 20;
    /** Text extracted from a markdown/text/PDF attachment is cut after this many characters. */
    public int maxAttachmentTextChars = 60_000;
    /** Summarize older messages (with the chat model) instead of silently dropping them from the window. */
    public boolean compactionEnabled = true;
    /** Compact when the not-yet-summarized history exceeds this estimate; 0 uses the message window only. */
    public int compactionTokenBudget = 6_000;
    /** Most recent messages always kept verbatim after compaction. */
    public int compactionKeepRecent = 6;
    /** Target length of the running summary. */
    public int compactionSummaryWords = 250;
}
