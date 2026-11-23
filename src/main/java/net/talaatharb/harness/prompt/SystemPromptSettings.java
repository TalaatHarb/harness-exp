package net.talaatharb.harness.prompt;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** System prompt template. Supports {@code {{date}}, {{time}}, {{model}}, {{os}}, {{user}}} placeholders. */
@JsonIgnoreProperties(ignoreUnknown = true)
public class SystemPromptSettings {
    /** Shared guidance so every profile visualizes with Mermaid, which the chat renders natively. */
    public static final String DIAGRAM_GUIDANCE = """
            When the user asks for a diagram or visualization, prefer a fenced ```mermaid code block
            (flowchart, sequence, class, state, ER, Gantt, mindmap, timeline, ...); it renders in the chat.
            Do not use %%{ }%% init directives or frontmatter in Mermaid diagrams.""";

    public static final String DEFAULT_TEMPLATE = """
            You are a helpful assistant running inside an experimental agent harness.
            Today is {{date}} and the local time is {{time}}. You are served by model {{model}}.
            Be concise and accurate. If you are unsure, say so.
            """ + DIAGRAM_GUIDANCE;

    public String template = DEFAULT_TEMPLATE;
}
