package net.talaatharb.harness.interaction;

import java.util.concurrent.CompletableFuture;

/**
 * Asks the human a question. The future completes with {@code null} when the user dismisses the window, and is
 * cancelled by the caller on timeout or Stop (implementations should then close the window).
 */
public interface UserPrompter {
    CompletableFuture<DecisionResponse> ask(DecisionRequest request);
}
