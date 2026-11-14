package dev.friendai.foundation.concurrent;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;

class CancelLinkTest {
    @Test
    void cancellingTheViewCancelsTheRequestBehindIt() {
        CompletableFuture<byte[]> request = new CompletableFuture<>();
        CompletableFuture<Void> view = new CompletableFuture<>();
        CancelLink.link(view, request);

        view.cancel(true);

        assertTrue(view.isCancelled());
        assertTrue(request.isCancelled(), "the request behind a cancelled view must stop");
    }

    @Test
    void aFinishedViewLeavesTheRequestAlone() {
        CompletableFuture<byte[]> request = new CompletableFuture<>();
        CompletableFuture<Void> view = new CompletableFuture<>();
        CancelLink.link(view, request);

        view.complete(null);

        assertFalse(request.isCancelled());
    }

    @Test
    void aFailedViewDoesNotCancelARequestThatAlreadyAnswered() {
        CompletableFuture<byte[]> request = CompletableFuture.completedFuture(new byte[] {1});
        CompletableFuture<Void> view = new CompletableFuture<>();
        CancelLink.link(view, request);

        view.completeExceptionally(new IllegalStateException("parse failed"));

        assertFalse(request.isCancelled());
    }
}
