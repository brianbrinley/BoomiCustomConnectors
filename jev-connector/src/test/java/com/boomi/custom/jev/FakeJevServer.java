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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

/**
 * In-process stand-in for the JEV API. Queue responses with {@link #enqueue}; inspect what was sent with
 * {@link #getRequests()}.
 *
 * <p>Requests are handled in parallel, so for tests that send several at once use {@link #respondWith} (the
 * answer depends on the request, not on arrival order), {@link #delay} to make requests overlap and
 * {@link #maxInFlight()} to see how many were being handled at the same time.
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
    private final ExecutorService handlers = Executors.newCachedThreadPool();
    private final AtomicInteger inFlight = new AtomicInteger();
    private final AtomicInteger maxInFlight = new AtomicInteger();
    private Function<Recorded, String> responder;
    private volatile long delayMs;

    public FakeJevServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            int now = inFlight.incrementAndGet();
            maxInFlight.accumulateAndGet(now, Math::max);
            try {
                String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                Canned canned;
                synchronized (this) {
                    Recorded recorded = new Recorded(exchange.getRequestURI().getPath(), exchange.getRequestHeaders(), body);
                    requests.add(recorded);
                    if (!responses.isEmpty()) {
                        canned = responses.poll();
                    } else if (responder != null) {
                        canned = new Canned(200, responder.apply(recorded), null);
                    } else {
                        canned = new Canned(500, "{\"error\":\"no response queued\"}", null);
                    }
                }
                if (delayMs > 0) {
                    try {
                        Thread.sleep(delayMs);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
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
            } finally {
                inFlight.decrementAndGet();
            }
        });
        server.setExecutor(handlers);
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

    /** Answers 200 with the body this function returns, whenever no queued response is left. */
    public synchronized FakeJevServer respondWith(Function<Recorded, String> bodyFor) {
        this.responder = bodyFor;
        return this;
    }

    /** Holds every request for this long before answering, so parallel requests overlap. */
    public FakeJevServer delay(long millis) {
        this.delayMs = millis;
        return this;
    }

    /** The largest number of requests that were being handled at the same moment. */
    public int maxInFlight() {
        return maxInFlight.get();
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
        handlers.shutdownNow();
    }
}
