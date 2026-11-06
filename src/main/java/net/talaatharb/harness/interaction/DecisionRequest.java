package net.talaatharb.harness.interaction;

import java.util.List;

/**
 * A question for the user. {@code options} may be empty (free-text only); {@code inputLabel} is {@code null} when no
 * text input is wanted.
 */
public record DecisionRequest(String question, List<String> options, String inputLabel) {

    /** Which layout the decision window uses. */
    public enum Mode {
        /** Exactly two options and no input: two prominent side-by-side buttons. */
        BINARY,
        /** No options: only a labelled input field. */
        INPUT_ONLY,
        /** Any other combination: a list of options with an optional input field. */
        CHOICES
    }

    public DecisionRequest {
        options = options == null ? List.of() : List.copyOf(options);
    }

    public boolean hasInput() {
        return inputLabel != null;
    }

    public Mode mode() {
        if (options.isEmpty()) {
            return Mode.INPUT_ONLY;
        }
        return options.size() == 2 && !hasInput() ? Mode.BINARY : Mode.CHOICES;
    }
}
