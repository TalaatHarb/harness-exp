package net.talaatharb.harness.trace;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.UnaryOperator;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import net.talaatharb.harness.context.ContextBuild;
import net.talaatharb.harness.context.ContextSection;
import net.talaatharb.harness.llm.ModelReply;
import net.talaatharb.harness.trace.TimelineEvent.Kind;

/**
 * Ordered record of what one agent run did: context sizes per contributor, model calls with latency and tokens,
 * tool calls, repairs, compaction and the final status. Thread-safe; bounded to {@link #MAX_EVENTS} events.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class RunTimeline {
    public static final int MAX_EVENTS = 300;
    static final int DETAIL_CHARS = 200;

    public Instant startedAt = Instant.now();
    public String model;
    public List<TimelineEvent> events = new ArrayList<>();
    /** Events not recorded because the run exceeded {@link #MAX_EVENTS}. */
    public int droppedEvents;
    @JsonIgnore
    private final long startNanos = System.nanoTime();

    public RunTimeline() {
    }

    public RunTimeline(String model) {
        this.model = model;
    }

    /** Milliseconds since the run started; pass to the recording methods as the step start. */
    public long elapsed() {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }

    public synchronized void add(TimelineEvent event) {
        if (events.size() >= MAX_EVENTS) {
            droppedEvents++;
        } else {
            events.add(event);
        }
    }

    public void context(ContextBuild build, int iteration, int sentMessages, int sentTokens) {
        add(new TimelineEvent(Kind.CONTEXT, "Context #" + iteration, elapsed(), 0,
                sentMessages + " messages", sentTokens, null, true, false, build.sections()));
    }

    public void model(long start, int iteration, ModelReply reply, int estimatedPrompt) {
        boolean reported = reply.promptTokens() != null || reply.completionTokens() != null;
        Integer prompt = reported ? reply.promptTokens() : Integer.valueOf(estimatedPrompt);
        Integer completion = reported ? reply.completionTokens()
                : Integer.valueOf(net.talaatharb.harness.context.TokenEstimator.estimate(reply.content()));
        add(new TimelineEvent(Kind.MODEL, "Model call #" + iteration, start, elapsed() - start, null, prompt,
                completion, !reported, false, null));
    }

    public void modelFailed(long start, int iteration, String error) {
        add(new TimelineEvent(Kind.MODEL, "Model call #" + iteration, start, elapsed() - start, shorten(error),
                null, null, false, true, null));
    }

    public void tool(String name, long start, boolean success, String output) {
        add(new TimelineEvent(Kind.TOOL, name, start, elapsed() - start, shorten(output), null, null, false,
                !success, null));
    }

    public void compaction(long start, String detail, ModelReply reply, boolean failed) {
        add(new TimelineEvent(Kind.COMPACTION, "Context compaction", start, elapsed() - start, shorten(detail),
                reply == null ? null : reply.promptTokens(), reply == null ? null : reply.completionTokens(),
                false, failed, null));
    }

    public void repair(String detail) {
        add(new TimelineEvent(Kind.REPAIR, "Repair request", elapsed(), 0, shorten(detail), null, null, false,
                true, null));
    }

    public void end(String status, String reason) {
        add(new TimelineEvent(Kind.END, status, elapsed(), 0, shorten(reason), null, null, false,
                !"COMPLETED".equals(status), null));
    }

    /** Copy with every label and detail passed through {@code redactor} (e.g. secret redaction). */
    public synchronized RunTimeline redacted(UnaryOperator<String> redactor) {
        RunTimeline copy = new RunTimeline(redactor.apply(model));
        copy.startedAt = startedAt;
        copy.droppedEvents = droppedEvents;
        for (TimelineEvent e : events) {
            copy.events.add(e.withText(redactor.apply(e.label()), redactor.apply(e.detail())));
        }
        return copy;
    }

    @JsonIgnore
    public synchronized long totalMillis() {
        return events.stream().mapToLong(e -> e.offsetMillis() + e.durationMillis()).max().orElse(0);
    }

    /** One-line summary, e.g. "2 model calls · 1 tool · ~1.2k → 85 tokens · 3.4 s". */
    @JsonIgnore
    public synchronized String headline() {
        List<TimelineEvent> models = of(Kind.MODEL);
        List<TimelineEvent> tools = of(Kind.TOOL);
        long failed = events.stream().filter(e -> e.failed() && e.kind() != Kind.END).count();
        boolean estimated = models.stream().anyMatch(TimelineEvent::estimated);
        int in = models.stream().mapToInt(e -> e.promptTokens() == null ? 0 : e.promptTokens()).sum();
        int out = models.stream().mapToInt(e -> e.completionTokens() == null ? 0 : e.completionTokens()).sum();
        StringBuilder sb = new StringBuilder();
        sb.append(models.size()).append(models.size() == 1 ? " model call" : " model calls");
        if (!tools.isEmpty()) {
            sb.append(" · ").append(tools.size()).append(tools.size() == 1 ? " tool" : " tools");
        }
        if (!of(Kind.COMPACTION).isEmpty()) {
            sb.append(" · compacted");
        }
        if (in > 0 || out > 0) {
            sb.append(" · ").append(estimated ? "~" : "").append(tokens(in)).append(" → ").append(tokens(out))
                    .append(" tokens");
        }
        if (failed > 0) {
            sb.append(" · ").append(failed).append(failed == 1 ? " issue" : " issues");
        }
        return sb.append(" · ").append(seconds(totalMillis())).toString();
    }

    /** Multi-line, human-readable rendering used by the chat timeline pane. */
    @JsonIgnore
    public synchronized String toText() {
        StringBuilder sb = new StringBuilder();
        if (model != null && !model.isBlank()) {
            sb.append("Model: ").append(model).append('\n');
        }
        for (TimelineEvent e : events) {
            sb.append(String.format(Locale.ROOT, "+%7.3fs  %-10s %s", e.offsetMillis() / 1000.0, e.kind(),
                    e.label()));
            if (e.durationMillis() > 0) {
                sb.append("  ").append(seconds(e.durationMillis()));
            }
            if (e.promptTokens() != null || e.completionTokens() != null) {
                String approx = e.estimated() ? "~" : "";
                if (e.kind() == Kind.CONTEXT) {
                    sb.append("  ").append(approx).append(e.promptTokens()).append(" tokens");
                } else {
                    sb.append("  in ").append(approx).append(e.promptTokens() == null ? "?" : e.promptTokens())
                            .append(" / out ").append(approx)
                            .append(e.completionTokens() == null ? "?" : e.completionTokens()).append(" tokens");
                }
            }
            if (e.failed() && e.kind() != Kind.END && e.kind() != Kind.REPAIR) {
                sb.append("  [failed]");
            }
            if (e.detail() != null && !e.detail().isBlank()) {
                sb.append("  · ").append(e.detail());
            }
            sb.append('\n');
            if (e.sections() != null) {
                for (ContextSection s : e.sections()) {
                    sb.append(String.format(Locale.ROOT, "              %-22s ~%6d tokens  %8d chars%s%n",
                            s.contributor(), s.estimatedTokens(), s.chars(),
                            s.messages() > 0 ? "  " + s.messages() + " msgs" : ""));
                }
            }
        }
        if (droppedEvents > 0) {
            sb.append("… ").append(droppedEvents).append(" more events not recorded\n");
        }
        sb.append("~ = estimated (about 4 characters per token)");
        return sb.toString();
    }

    private List<TimelineEvent> of(Kind kind) {
        return events.stream().filter(e -> e.kind() == kind).toList();
    }

    static String shorten(String text) {
        if (text == null) {
            return null;
        }
        String flat = text.replaceAll("\\s+", " ").strip();
        return flat.length() > DETAIL_CHARS ? flat.substring(0, DETAIL_CHARS) + "…" : flat;
    }

    static String tokens(int count) {
        return count >= 10_000 ? String.format(Locale.ROOT, "%.1fk", count / 1000.0) : String.valueOf(count);
    }

    static String seconds(long millis) {
        return String.format(Locale.ROOT, "%.1f s", millis / 1000.0);
    }
}
