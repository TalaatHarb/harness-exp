package net.talaatharb.harness.agent;

import java.time.Duration;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** User-tunable loop limits; effective values are clamped to {@link AgentLoopLimits}. */
@JsonIgnoreProperties(ignoreUnknown = true)
public class AgentLoopSettings {
    public int maxIterations = AgentLoopLimits.DEFAULT_MAX_ITERATIONS;
    public int maxToolCalls = AgentLoopLimits.DEFAULT_MAX_TOOL_CALLS;
    public int maxDurationSeconds = AgentLoopLimits.DEFAULT_MAX_DURATION_SECONDS;
    public int maxIdenticalToolCalls = AgentLoopLimits.DEFAULT_MAX_IDENTICAL_TOOL_CALLS;
    public int maxConsecutiveErrors = AgentLoopLimits.DEFAULT_MAX_CONSECUTIVE_ERRORS;

    @JsonIgnore
    public int effectiveMaxIterations() {
        return AgentLoopLimits.clamp(maxIterations, AgentLoopLimits.HARD_MAX_ITERATIONS);
    }

    @JsonIgnore
    public int effectiveMaxToolCalls() {
        return AgentLoopLimits.clamp(maxToolCalls, AgentLoopLimits.HARD_MAX_TOOL_CALLS);
    }

    @JsonIgnore
    public Duration effectiveMaxDuration() {
        Duration configured = Duration.ofSeconds(Math.max(1, maxDurationSeconds));
        return configured.compareTo(AgentLoopLimits.HARD_MAX_DURATION) > 0 ? AgentLoopLimits.HARD_MAX_DURATION
                : configured;
    }

    @JsonIgnore
    public int effectiveMaxIdenticalToolCalls() {
        return AgentLoopLimits.clamp(maxIdenticalToolCalls, AgentLoopLimits.HARD_MAX_IDENTICAL_TOOL_CALLS);
    }

    @JsonIgnore
    public int effectiveMaxConsecutiveErrors() {
        return AgentLoopLimits.clamp(maxConsecutiveErrors, AgentLoopLimits.HARD_MAX_CONSECUTIVE_ERRORS);
    }
}
