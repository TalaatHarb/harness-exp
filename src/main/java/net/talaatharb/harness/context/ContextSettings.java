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
}
