package dev.friendai.agent.llm;

import java.net.URI;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * Port for a streaming provider request. Chunks are delivered in arrival order; the future completes at the end of the
 * body, fails on a transport/status error, and {@code cancel(true)} aborts the subscription. The implementation owns
 * timeouts and header placement; credentials go in headers, never in a URL.
 */
public interface StreamingHttpTransport {
    CompletableFuture<Void> postStream(URI uri, Map<String, String> headers, String body, Consumer<byte[]> onChunk);
}
