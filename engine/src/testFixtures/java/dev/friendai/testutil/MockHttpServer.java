package dev.friendai.testutil;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;

/** A tiny scripted HTTP server for tests. Responses are served from a FIFO queue. */
public final class MockHttpServer implements AutoCloseable {
    public record RecordedRequest(String method, String path, Map<String, String> headers, String body) {
        /** Case-insensitive header lookup. */
        public String header(String name) {
            return headers.get(name.toLowerCase());
        }
    }

    private record Canned(int status, byte[] body, long delayMillis) {}

    private final HttpServer server;
    private final Queue<Canned> queue = new ArrayDeque<>();
    private final List<RecordedRequest> requests = Collections.synchronizedList(new ArrayList<>());

    public MockHttpServer() {
        try {
            server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        server.createContext("/", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            Map<String, String> headers = new HashMap<>();
            exchange.getRequestHeaders().forEach((k, v) -> headers.put(k.toLowerCase(), v.get(0)));
            requests.add(new RecordedRequest(
                    exchange.getRequestMethod(), exchange.getRequestURI().getPath(), headers, body));
            Canned canned;
            synchronized (queue) {
                canned = queue.poll();
            }
            if (canned == null) {
                canned = new Canned(500, "{\"error\":\"mock queue empty\"}".getBytes(StandardCharsets.UTF_8), 0);
            }
            byte[] out = canned.body();
            if (canned.delayMillis() > 0) {
                try {
                    Thread.sleep(canned.delayMillis());
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(canned.status(), out.length == 0 ? -1 : out.length);
            if (out.length > 0) {
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(out);
                }
            }
            exchange.close();
        });
        server.start();
    }

    public URI baseUri() {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort());
    }

    public String baseUrl() {
        return baseUri().toString();
    }

    public void enqueue(int status, String body) {
        enqueue(status, body.getBytes(StandardCharsets.UTF_8));
    }

    public void enqueue(int status, byte[] body) {
        enqueueDelayed(status, body, 0);
    }

    /** Serves the response after a delay, so tests can overlap two clients deterministically. */
    public void enqueueDelayed(int status, byte[] body, long delayMillis) {
        if (delayMillis < 0) throw new IllegalArgumentException("negative delay");
        synchronized (queue) {
            queue.add(new Canned(status, body.clone(), delayMillis));
        }
    }

    public List<RecordedRequest> requests() {
        synchronized (requests) {
            return new ArrayList<>(requests);
        }
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
