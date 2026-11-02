package net.talaatharb.harness.agent;

public record AgentRunResult(Status status, String reply, String reason, int iterations, int toolCalls) {

    public enum Status {
        COMPLETED, BLOCKED, LIMIT_REACHED, CANCELLED, ABORTED, ERROR
    }
}
