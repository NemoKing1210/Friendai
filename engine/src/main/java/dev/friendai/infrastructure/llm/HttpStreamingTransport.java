package dev.friendai.infrastructure.llm;

import dev.friendai.agent.llm.StreamingHttpTransport;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/** JVM HTTP adapter for {@link StreamingHttpTransport}; headers carry credentials, the URL never does. */
public final class HttpStreamingTransport implements StreamingHttpTransport {
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(60);

    private final HttpClient client;

    public HttpStreamingTransport() {
        this(HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build());
    }

    public HttpStreamingTransport(HttpClient client) {
        this.client = Objects.requireNonNull(client);
    }

    @Override
    public CompletableFuture<Void> postStream(
            URI uri, Map<String, String> headers, String body, Consumer<byte[]> onChunk) {
        if (uri == null || body == null || onChunk == null) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("uri, body and consumer are required"));
        }
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
                .timeout(REQUEST_TIMEOUT)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (headers != null) {
            headers.forEach((name, value) -> {
                if (name != null && value != null) {
                    builder.header(name, value);
                }
            });
        }
        CompletableFuture<Void> done = new CompletableFuture<>();
        AtomicReference<Flow.Subscription> subscription = new AtomicReference<>();
        client.sendAsync(builder.build(), HttpResponse.BodyHandlers.ofPublisher())
                .whenComplete((response, error) -> {
                    if (error != null) {
                        done.completeExceptionally(error);
                        return;
                    }
                    if (response.statusCode() / 100 != 2) {
                        // A refused body is never read; subscribing and cancelling releases it instead of leaking it.
                        release(response.body());
                        done.completeExceptionally(new IOException("HTTP " + response.statusCode()));
                        return;
                    }
                    response.body().subscribe(new Flow.Subscriber<List<ByteBuffer>>() {
                        @Override
                        public void onSubscribe(Flow.Subscription value) {
                            subscription.set(value);
                            if (done.isCancelled()) {
                                value.cancel();
                                return;
                            }
                            value.request(Long.MAX_VALUE);
                        }

                        @Override
                        public void onNext(List<ByteBuffer> buffers) {
                            if (done.isCancelled()) {
                                Flow.Subscription value = subscription.get();
                                if (value != null) {
                                    value.cancel();
                                }
                                return;
                            }
                            try {
                                onChunk.accept(bytes(buffers));
                            } catch (RuntimeException failed) {
                                Flow.Subscription value = subscription.get();
                                if (value != null) {
                                    value.cancel();
                                }
                                done.completeExceptionally(failed);
                            }
                        }

                        @Override
                        public void onError(Throwable error) {
                            done.completeExceptionally(error);
                        }

                        @Override
                        public void onComplete() {
                            done.complete(null);
                        }
                    });
                });
        done.whenComplete((ignored, error) -> {
            if (done.isCancelled()) {
                Flow.Subscription value = subscription.get();
                if (value != null) {
                    value.cancel();
                }
            }
        });
        return done;
    }

    /** Releases an unread response body; without a subscriber the connection would stay open until collected. */
    private static void release(Flow.Publisher<List<ByteBuffer>> body) {
        body.subscribe(new Flow.Subscriber<>() {
            @Override
            public void onSubscribe(Flow.Subscription subscription) {
                subscription.cancel();
            }

            @Override
            public void onNext(List<ByteBuffer> item) {}

            @Override
            public void onError(Throwable error) {}

            @Override
            public void onComplete() {}
        });
    }

    private static byte[] bytes(List<ByteBuffer> buffers) {
        int size = buffers.stream().mapToInt(ByteBuffer::remaining).sum();
        byte[] bytes = new byte[size];
        int offset = 0;
        for (ByteBuffer buffer : buffers) {
            ByteBuffer copy = buffer.duplicate();
            int length = copy.remaining();
            copy.get(bytes, offset, length);
            offset += length;
        }
        return bytes;
    }
}
