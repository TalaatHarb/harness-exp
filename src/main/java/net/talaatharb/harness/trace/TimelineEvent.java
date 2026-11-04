package net.talaatharb.harness.trace;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

import net.talaatharb.harness.context.ContextSection;

/**
 * One step of an agent run.
 *
 * @param offsetMillis     start, relative to the beginning of the run
 * @param promptTokens     input tokens (provider-reported unless {@code estimated})
 * @param completionTokens output tokens (provider-reported unless {@code estimated})
 * @param estimated        token counts are {@link net.talaatharb.harness.context.TokenEstimator} estimates
 * @param sections         per-contributor sizes for {@link Kind#CONTEXT} events
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record TimelineEvent(Kind kind, String label, long offsetMillis, long durationMillis, String detail,
        Integer promptTokens, Integer completionTokens, boolean estimated, boolean failed,
        List<ContextSection> sections) {

    public enum Kind {
        /** Context assembled for a model call. */
        CONTEXT,
        /** Older history summarized into the running conversation summary. */
        COMPACTION,
        MODEL,
        TOOL,
        /** The model was asked to fix its reply (malformed tool call, response contract). */
        REPAIR,
        /** Terminal status of the run. */
        END
    }

    public TimelineEvent {
        sections = sections == null || sections.isEmpty() ? null : List.copyOf(sections);
    }

    TimelineEvent withText(String newLabel, String newDetail) {
        return new TimelineEvent(kind, newLabel, offsetMillis, durationMillis, newDetail, promptTokens,
                completionTokens, estimated, failed, sections);
    }
}
