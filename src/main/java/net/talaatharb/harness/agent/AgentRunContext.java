package net.talaatharb.harness.agent;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import net.talaatharb.harness.config.HarnessSettings;
import net.talaatharb.harness.llm.Attachment;
import net.talaatharb.harness.llm.ChatMessage;
import net.talaatharb.harness.llm.ModelReply;
import net.talaatharb.harness.tools.ToolCall;
import net.talaatharb.harness.tools.ToolResult;

/** Per-run state shared with hooks. */
public class AgentRunContext {
    private final String runId = UUID.randomUUID().toString().substring(0, 8);
    private final Instant startedAt = Instant.now();
    private final String userMessage;
    private final List<Attachment> attachments;
    private final HarnessSettings settings;
    private final AtomicBoolean cancelled;
    private final Map<String, Integer> toolCallCounts = new HashMap<>();
    private final Map<String, Object> attributes = new HashMap<>();
    private int iteration;
    private int toolCalls;
    private int consecutiveErrors;
    private List<ChatMessage> lastContext = List.of();
    private ModelReply lastReply;
    private ToolCall lastToolCall;
    private ToolResult lastToolResult;
    private Throwable lastError;
    private volatile String abortReason;
    private final StringBuffer streamedReply = new StringBuffer();
    private final java.util.Set<String> deniedTools = new java.util.HashSet<>();
    private final java.util.Set<String> attemptedTools = new java.util.HashSet<>();
    private final java.util.Set<String> successfulTools = new java.util.HashSet<>();

    AgentRunContext(String userMessage, List<Attachment> attachments, HarnessSettings settings,
            AtomicBoolean cancelled) {
        this.userMessage = userMessage;
        this.attachments = attachments == null ? List.of() : List.copyOf(attachments);
        this.settings = settings;
        this.cancelled = cancelled;
    }

    public String runId() {
        return runId;
    }

    public Instant startedAt() {
        return startedAt;
    }

    public Duration elapsed() {
        return Duration.between(startedAt, Instant.now());
    }

    public String userMessage() {
        return userMessage;
    }

    /** Files attached to the user message (as given; guardrails may modify the copies in the transcript). */
    public List<Attachment> attachments() {
        return attachments;
    }

    public HarnessSettings settings() {
        return settings;
    }

    public int iteration() {
        return iteration;
    }

    public int toolCalls() {
        return toolCalls;
    }

    public List<ChatMessage> lastContext() {
        return lastContext;
    }

    public ModelReply lastReply() {
        return lastReply;
    }

    public ToolCall lastToolCall() {
        return lastToolCall;
    }

    public ToolResult lastToolResult() {
        return lastToolResult;
    }

    public Throwable lastError() {
        return lastError;
    }

    /** Scratch space for hooks to share state across hook points. */
    public Map<String, Object> attributes() {
        return attributes;
    }

    public void abort(String reason) {
        abortReason = reason == null ? "aborted by hook" : reason;
    }

    public String abortReason() {
        return abortReason;
    }

    public boolean cancelled() {
        return cancelled.get();
    }

    void resetStream() {
        streamedReply.setLength(0);
    }

    void appendStream(String token) {
        if (token != null) streamedReply.append(token);
    }

    String partialReply() {
        return streamedReply.toString();
    }

    boolean wasDenied(String tool) {
        return deniedTools.contains(tool);
    }

    void deny(String tool) {
        deniedTools.add(tool);
    }

    boolean attempted(String tool) {
        return attemptedTools.contains(tool);
    }

    boolean succeeded(String tool) {
        return successfulTools.contains(tool);
    }

    void nextIteration() {
        iteration++;
    }

    int recordToolCall(ToolCall call) {
        toolCalls++;
        attemptedTools.add(call.name());
        lastToolCall = call;
        return toolCallCounts.merge(call.signature(), 1, Integer::sum);
    }

    void setLastContext(List<ChatMessage> context) {
        lastContext = context;
    }

    void setLastReply(ModelReply reply) {
        lastReply = reply;
    }

    void setLastToolResult(ToolResult result) {
        lastToolResult = result;
        if (result.success() && lastToolCall != null) successfulTools.add(lastToolCall.name());
    }

    int recordError(Throwable error) {
        lastError = error;
        return ++consecutiveErrors;
    }

    void clearErrors() {
        consecutiveErrors = 0;
    }
}
