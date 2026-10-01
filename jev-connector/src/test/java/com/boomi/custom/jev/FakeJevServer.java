package com.boomi.custom.jev;

import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;

/**
 * In-process stand-in for the JEV API. Queue responses with {@link #enqueue}; inspect what was sent with
 * {@link #getRequests()}.
 */
public final class FakeJevServer implements AutoCloseable {

    public static final class Recorded {
        public final String path;
        public final Map<String, List<String>> headers;
        public final String body;

        Recorded(String path, Map<String, List<String>> headers, String body) {
            this.path = path;
            this.headers = headers;
            this.body = body;
        }

        public String header(String name) {
            for (Map.Entry<String, List<String>> e : headers.entrySet()) {
                if (e.getKey().equalsIgnoreCase(name)) {
                    return e.getValue().get(0);
                }
            }
            return null;
        }
    }

    private static final class Canned {
        final int status;
        final String body;
        final String retryAfter;

        Canned(int status, String body, String retryAfter) {
            this.status = status;
            this.body = body;
            this.retryAfter = retryAfter;
        }
    }

    private final HttpServer server;
    private final Deque<Canned> responses = new ArrayDeque<>();
    private final List<Recorded> requests = new ArrayList<>();

    public FakeJevServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            Canned canned;
            synchronized (this) {
                requests.add(new Recorded(exchange.getRequestURI().getPath(), exchange.getRequestHeaders(), body));
                canned = responses.isEmpty() ? new Canned(500, "{\"error\":\"no response queued\"}", null) : responses.poll();
            }
            byte[] out = canned.body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            if (canned.retryAfter != null) {
                exchange.getResponseHeaders().add("Retry-After", canned.retryAfter);
            }
            exchange.sendResponseHeaders(canned.status, out.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(out);
            }
        });
        server.start();
    }

    public synchronized FakeJevServer enqueue(int status, String body) {
        responses.add(new Canned(status, body, null));
        return this;
    }

    public synchronized FakeJevServer enqueue(int status, String body, String retryAfter) {
        responses.add(new Canned(status, body, retryAfter));
        return this;
    }

    public synchronized List<Recorded> getRequests() {
        return new ArrayList<>(requests);
    }

    public String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
