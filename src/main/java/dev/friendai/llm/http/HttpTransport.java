package dev.friendai.llm.http;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.friendai.agent.llm.JsonHttpTransport;
import dev.friendai.agent.llm.RequestAttempt;
import dev.friendai.agent.llm.RequestTelemetry;
import dev.friendai.agent.llm.model.LlmErrorKind;
import dev.friendai.agent.llm.model.LlmException;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/** JSON over HTTP with error mapping and retries. Never leaks API keys into exception messages. */
public final class HttpTransport implements JsonHttpTransport {
    private static final Pattern KEY_LIKE = Pattern.compile("(sk|key|AIza)[-_A-Za-z0-9]{6,}");
    private static final int MAX_MESSAGE_CHARS = 200;

    private final HttpClient client;
    private final Duration timeout;
    private final int maxRetries;
    private final Duration backoffBase;

    public HttpTransport(HttpClient client, Duration timeout, int maxRetries, Duration backoffBase) {
        this.client = client;
        this.timeout = timeout;
        this.maxRetries = maxRetries;
        this.backoffBase = backoffBase;
    }

    public CompletableFuture<JsonObject> postJson(URI uri, Map<String, String> headers, JsonObject body) {
        return postJson(uri, headers, body, RequestTelemetry.NONE);
    }

    @Override
    public CompletableFuture<JsonObject> postJson(
            URI uri, Map<String, String> headers, JsonObject body, RequestTelemetry telemetry) {
        HttpRequest.Builder b = HttpRequest.newBuilder(uri)
                .timeout(timeout)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8));
        headers.forEach(b::header);
        long payloadBytes = body.toString().getBytes(StandardCharsets.UTF_8).length;
        return execute(b.build(), secretsOf(headers), 0, telemetry, payloadBytes);
    }

    public CompletableFuture<JsonObject> getJson(URI uri, Map<String, String> headers) {
        return getJson(uri, headers, RequestTelemetry.NONE);
    }

    @Override
    public CompletableFuture<JsonObject> getJson(URI uri, Map<String, String> headers, RequestTelemetry telemetry) {
        HttpRequest.Builder b = HttpRequest.newBuilder(uri).timeout(timeout).GET();
        headers.forEach(b::header);
        return execute(b.build(), secretsOf(headers), 0, telemetry, 0L);
    }

    private CompletableFuture<JsonObject> execute(
            HttpRequest request, List<String> secrets, int attempt, RequestTelemetry telemetry, long payloadBytes) {
        RequestTelemetry observation = telemetry == null ? RequestTelemetry.NONE : telemetry;
        long started = System.nanoTime();
        observation.accept(new RequestAttempt(attempt + 1, RequestAttempt.START, 0, "", -1L, 0L, payloadBytes));
        return client.sendAsync(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
                .handle((response, error) -> {
                    long elapsedMs = Math.max(0L, (System.nanoTime() - started) / 1_000_000L);
                    LlmException failure;
                    int status = 0;
                    if (error != null) {
                        failure = mapTransportError(unwrap(error));
                    } else if (response.statusCode() / 100 != 2) {
                        status = response.statusCode();
                        failure = mapStatus(response.statusCode(), response.body(), secrets);
                    } else {
                        try {
                            JsonElement parsed = JsonParser.parseString(response.body());
                            if (!parsed.isJsonObject()) {
                                throw new IllegalStateException("not an object");
                            }
                            observation.accept(new RequestAttempt(
                                    attempt + 1,
                                    RequestAttempt.FINISH,
                                    response.statusCode(),
                                    "",
                                    elapsedMs,
                                    0L,
                                    payloadBytes));
                            return CompletableFuture.completedFuture(parsed.getAsJsonObject());
                        } catch (RuntimeException e) {
                            failure = new LlmException(LlmErrorKind.PARSE, "Response is not valid JSON");
                        }
                    }
                    if (failure.kind().retryable() && attempt < maxRetries) {
                        long delayMs = backoffBase.toMillis() << attempt;
                        observation.accept(new RequestAttempt(
                                attempt + 1,
                                RequestAttempt.RETRY,
                                status,
                                kindToken(failure),
                                elapsedMs,
                                delayMs,
                                payloadBytes));
                        return CompletableFuture.supplyAsync(
                                        () -> null, CompletableFuture.delayedExecutor(delayMs, TimeUnit.MILLISECONDS))
                                .thenCompose(
                                        ignored -> execute(request, secrets, attempt + 1, observation, payloadBytes));
                    }
                    observation.accept(new RequestAttempt(
                            attempt + 1,
                            RequestAttempt.FINISH,
                            status,
                            kindToken(failure),
                            elapsedMs,
                            0L,
                            payloadBytes));
                    return CompletableFuture.<JsonObject>failedFuture(failure);
                })
                .thenCompose(f -> f);
    }

    private static String kindToken(LlmException failure) {
        return failure.kind().name().toLowerCase(Locale.ROOT);
    }

    private static Throwable unwrap(Throwable t) {
        while (t instanceof CompletionException && t.getCause() != null) {
            t = t.getCause();
        }
        return t;
    }

    private static LlmException mapTransportError(Throwable t) {
        if (t instanceof HttpTimeoutException) {
            return new LlmException(LlmErrorKind.TIMEOUT, "Request timed out");
        }
        if (t instanceof IOException) {
            return new LlmException(
                    LlmErrorKind.NETWORK, "Network error: " + t.getClass().getSimpleName());
        }
        return new LlmException(
                LlmErrorKind.NETWORK, "Request failed: " + t.getClass().getSimpleName());
    }

    private static LlmException mapStatus(int status, String body, List<String> secrets) {
        LlmErrorKind kind;
        if (status == 401 || status == 403) {
            kind = LlmErrorKind.AUTH;
        } else if (status == 429) {
            kind = LlmErrorKind.RATE_LIMIT;
        } else if (status >= 500) {
            kind = LlmErrorKind.SERVER;
        } else {
            kind = LlmErrorKind.BAD_REQUEST;
        }
        String detail = sanitize(extractMessage(body), secrets);
        return new LlmException(kind, "HTTP " + status + ": " + detail, status);
    }

    private static String extractMessage(String body) {
        try {
            JsonObject o = JsonParser.parseString(body).getAsJsonObject();
            JsonElement err = o.get("error");
            if (err != null && err.isJsonObject() && err.getAsJsonObject().has("message")) {
                return err.getAsJsonObject().get("message").getAsString();
            }
            if (err != null && err.isJsonPrimitive()) {
                return err.getAsString();
            }
            if (o.has("message")) {
                return o.get("message").getAsString();
            }
        } catch (RuntimeException ignored) {
            // fall through to the raw body
        }
        return body == null ? "" : body;
    }

    private static String sanitize(String text, List<String> secrets) {
        String out = text;
        for (String secret : secrets) {
            out = out.replace(secret, "***");
        }
        out = KEY_LIKE.matcher(out).replaceAll("***");
        out = out.strip();
        return out.length() > MAX_MESSAGE_CHARS ? out.substring(0, MAX_MESSAGE_CHARS) : out;
    }

    private static List<String> secretsOf(Map<String, String> headers) {
        List<String> secrets = new ArrayList<>();
        for (Map.Entry<String, String> e : headers.entrySet()) {
            String name = e.getKey().toLowerCase();
            String value = e.getValue();
            if (value == null || value.isEmpty()) {
                continue;
            }
            if (name.equals("authorization")) {
                secrets.add(value.startsWith("Bearer ") ? value.substring(7) : value);
            } else if (name.equals("x-api-key") || name.equals("x-goog-api-key")) {
                secrets.add(value);
            }
        }
        return secrets;
    }
}
