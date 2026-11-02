package net.talaatharb.harness.history;

import java.time.Instant;

/** Lightweight index entry so the history list doesn't load every transcript. */
public record ConversationSummary(String id, String title, Instant updatedAt, int turns) {
}
