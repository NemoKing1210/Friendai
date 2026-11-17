package dev.friendai.infrastructure.llm;

import dev.friendai.agent.llm.RequestAttempt;
import dev.friendai.agent.llm.RequestTelemetry;
import dev.friendai.agent.llm.StreamingHttpTransport;
import dev.friendai.agent.llm.model.LlmErrorKind;
import dev.friendai.agent.llm.model.LlmException;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/** JVM HTTP adapter for {@link StreamingHttpTransport}; headers carry credentials, the URL never does. */
public final class HttpStreamingTransport implements StreamingHttpTransport {
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(60);

    private final HttpClient client;
    private final Duration timeout;

    public HttpStreamingTransport() {
        this(HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build());
    }

    public HttpStreamingTransport(HttpClient client) {
        this(client, REQUEST_TIMEOUT);
    }

    public HttpStreamingTransport(HttpClient client, Duration timeout) {
        this.client = Objects.requireNonNull(client);
        this.timeout = Objects.requireNonNull(timeout);
        if (timeout.isZero() || timeout.isNegative()) throw new IllegalArgumentException("timeout must be positive");
    }

    @Override
    public CompletableFuture<Void> postStream(
            URI uri, Map<String, String> headers, String body, Consumer<byte[]> onChunk) {
        return postStream(uri, headers, body, onChunk, RequestTelemetry.NONE);
    }

    @Override
    public CompletableFuture<Void> postStream(
            URI uri, Map<String, String> headers, String body, Consumer<byte[]> onChunk, RequestTelemetry telemetry) {
        if (uri == null || body == null || onChunk == null) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("uri, body and consumer are required"));
        }
        byte[] payload = body.getBytes(StandardCharsets.UTF_8);
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
                .timeout(timeout)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(payload));
        if (headers != null) {
            headers.forEach((name, value) -> {
                if (name != null && value != null) {
                    builder.header(name, value);
                }
            });
        }
        RequestTelemetry observation = telemetry == null ? RequestTelemetry.NONE : telemetry;
        HttpRequest request = builder.build();
        long started = System.nanoTime();
        observation.accept(new RequestAttempt(1, RequestAttempt.START, 0, "", 0L, 0L, payload.length));

        // ofPublisher completes the exchange at headers. Keep the deadline armed until the body itself settles.
        CompletableFuture<Void> done = new CompletableFuture<>();
        done.orTimeout(timeout.toNanos(), TimeUnit.NANOSECONDS);
        var exchange = client.sendAsync(request, HttpResponse.BodyHandlers.ofPublisher());
        CompletableFuture<Void> result = new CompletableFuture<>();
        AtomicReference<Flow.Subscription> subscription = new AtomicReference<>();
        AtomicInteger status = new AtomicInteger();
        result.whenComplete((ignored, error) -> {
            if (result.isCancelled()) done.cancel(true);
        });
        done.whenComplete((ignored, error) -> {
            if (error != null) {
                exchange.cancel(true);
                Flow.Subscription active = subscription.get();
                if (active != null) active.cancel();
            }
            Throwable failure = error instanceof CompletionException wrapped ? wrapped.getCause() : error;
            boolean timedOut = failure instanceof TimeoutException || failure instanceof HttpTimeoutException;
            int code = status.get();
            String kind;
            if (done.isCancelled()) kind = "cancelled";
            else if (timedOut) kind = "timeout";
            else if (failure == null) kind = "";
            else if (code != 0 && code / 100 != 2) kind = "http";
            else if (failure instanceof IOException) kind = "network";
            else kind = "stream";
            long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
            observation.accept(new RequestAttempt(1, RequestAttempt.FINISH, code, kind, elapsed, 0L, payload.length));
            if (timedOut) {
                result.completeExceptionally(new LlmException(LlmErrorKind.TIMEOUT, "Stream request timed out"));
            } else if (failure != null) {
                result.completeExceptionally(failure);
            } else {
                result.complete(null);
            }
        });
        exchange.whenComplete((response, error) -> {
            if (error != null) {
                done.completeExceptionally(error);
                return;
            }
            status.set(response.statusCode());
            if (done.isDone() || response.statusCode() / 100 != 2) {
                release(response.body());
                done.completeExceptionally(new IOException("HTTP " + response.statusCode()));
                return;
            }
            response.body().subscribe(new Flow.Subscriber<List<ByteBuffer>>() {
                @Override
                public void onSubscribe(Flow.Subscription value) {
                    subscription.set(value);
                    if (done.isDone()) {
                        value.cancel();
                        return;
                    }
                    value.request(Long.MAX_VALUE);
                }

                @Override
                public void onNext(List<ByteBuffer> buffers) {
                    if (done.isDone()) return;
                    try {
                        onChunk.accept(bytes(buffers));
                    } catch (RuntimeException failed) {
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
        return result;
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
