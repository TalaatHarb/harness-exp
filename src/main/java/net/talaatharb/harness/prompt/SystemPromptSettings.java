package net.talaatharb.harness.prompt;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** System prompt template. Supports {@code {{date}}, {{time}}, {{model}}, {{os}}, {{user}}} placeholders. */
@JsonIgnoreProperties(ignoreUnknown = true)
public class SystemPromptSettings {
    public static final String DEFAULT_TEMPLATE = """
            You are a helpful assistant running inside an experimental agent harness.
            Today is {{date}} and the local time is {{time}}. You are served by model {{model}}.
            Be concise and accurate. If you are unsure, say so.""";

    public String template = DEFAULT_TEMPLATE;
}
