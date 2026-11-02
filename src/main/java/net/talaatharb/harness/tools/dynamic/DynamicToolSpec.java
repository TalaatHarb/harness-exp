package net.talaatharb.harness.tools.dynamic;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Serialized input of {@code create_tool}. One JSON file per successfully created tool is kept in the dynamic
 * tools folder and recompiled at application start.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class DynamicToolSpec {
    public String name;
    public String description;
    public String argumentsHint;
    public String sourceCode;
    /** ISO-8601 timestamp of the last successful creation. */
    public String createdAt;

    public DynamicToolSpec() {
    }

    public DynamicToolSpec(String name, String description, String argumentsHint, String sourceCode) {
        this.name = name;
        this.description = description;
        this.argumentsHint = argumentsHint;
        this.sourceCode = sourceCode;
    }
}