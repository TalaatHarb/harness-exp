package net.talaatharb.harness.guardrails;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public class GuardrailSettings {
    public boolean enabled = true;
    public Set<String> disabledGuardrails = new HashSet<>();
    public int maxInputChars = 8_000;
    /** Case-insensitive terms that block input, tool calls and output. */
    public List<String> blockedTerms = new ArrayList<>();
}
