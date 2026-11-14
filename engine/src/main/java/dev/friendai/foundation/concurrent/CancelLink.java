package dev.friendai.foundation.concurrent;

import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;

/**
 * Links a derived future to the work behind it, so cancelling the caller's view cancels the request itself.
 *
 * <p>A stage built with {@code thenApply} or {@code handle} owns its own cancellation: cancelling the stage leaves the
 * upstream running. Speech output cancels an obsolete synthesis when the owner stops a reply, and the cloud request
 * behind it must stop too, or it keeps the connection until the provider answers.
 */
public final class CancelLink {
    private CancelLink() {}

    /** Cancels {@code upstream} when {@code dependent} is cancelled. */
    public static void link(CompletableFuture<?> dependent, CompletableFuture<?> upstream) {
        dependent.whenComplete((value, error) -> {
            if (error instanceof CancellationException) {
                upstream.cancel(true);
            }
        });
    }
}
