package net.talaatharb.harness.interaction;

/** The user's answer: the chosen option and/or the typed text; either is {@code null} when not provided. */
public record DecisionResponse(String choice, String input) {
}
