package com.boomi.custom.jev.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Minimal HTTP client for the JEV decision endpoint. Retries 429, 502, 503, 504 and network failures with
 * exponential backoff, honouring {@code Retry-After} (in seconds) when JEV sends it.
 *
 * <p>Safe to share between threads. Clients created with {@link #withRateLimitGate(RateLimitGate)} also wait at
 * the shared gate before each request and close it when JEV answers 429, so parallel workers back off together.
 */
public class JevClient {

    private static final Logger LOG = Logger.getLogger(JevClient.class.getName());
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final long DEFAULT_BACKOFF_MS = 500L;
    private static final long MAX_BACKOFF_MS = 30_000L;

    /** Pluggable so tests don't actually wait. */
    public interface Sleeper {
        void sleep(long millis) throws InterruptedException;
    }

    private final JevSettings settings;
    private final long baseBackoffMs;
    private final Sleeper sleeper;
    private final RateLimitGate gate;

    public JevClient(JevSettings settings) {
        this(settings, DEFAULT_BACKOFF_MS, Thread::sleep);
    }

    public JevClient(JevSettings settings, long baseBackoffMs, Sleeper sleeper) {
        this(settings, baseBackoffMs, sleeper, null);
    }

    private JevClient(JevSettings settings, long baseBackoffMs, Sleeper sleeper, RateLimitGate gate) {
        this.settings = settings;
        this.baseBackoffMs = baseBackoffMs;
        this.sleeper = sleeper;
        this.gate = gate;
    }

    /** A client with the same settings whose requests share the given rate-limit pause. */
    public JevClient withRateLimitGate(RateLimitGate sharedGate) {
        return new JevClient(settings, baseBackoffMs, sleeper, sharedGate);
    }

    public JevSettings getSettings() {
        return settings;
    }

    /**
     * POSTs the request to the decision endpoint. Returns the final response (which may be a non-2xx status once
     * retries are exhausted); throws only when the last attempt failed at the network level.
     */
    public JevHttpResponse decide(JsonNode request) throws IOException {
        byte[] body = MAPPER.writeValueAsBytes(request);
        JevHttpResponse response = null;
        IOException lastError = null;

        for (int attempt = 0; attempt <= settings.getMaxRetries(); attempt++) {
            if (attempt > 0) {
                long delay = backoffFor(attempt, response);
                LOG.log(Level.INFO, "Retrying JEV request (attempt {0} of {1}) in {2} ms",
                        new Object[] {attempt + 1, settings.getMaxRetries() + 1, delay});
                pause(delay);
            }
            awaitGate();
            try {
                response = post(body);
                lastError = null;
                if (gate != null && response.getStatusCode() == 429) {
                    // Tell the other workers to hold off for as long as this request would back off
                    gate.pauseFor(backoffFor(attempt + 1, response));
                }
                if (!isRetryable(response.getStatusCode())) {
                    return response;
                }
            } catch (IOException e) {
                lastError = e;
                response = null;
                LOG.log(Level.WARNING, "JEV request failed: {0}", e.toString());
            }
        }
        if (lastError != null) {
            throw lastError;
        }
        return response;
    }

    static boolean isRetryable(int status) {
        return status == 429 || status == 502 || status == 503 || status == 504;
    }

    private long backoffFor(int attempt, JevHttpResponse previous) {
        if (previous != null && previous.retryAfterSeconds() != null) {
            return Math.min(previous.retryAfterSeconds() * 1000L, MAX_BACKOFF_MS);
        }
        return Math.min(baseBackoffMs * (1L << (attempt - 1)), MAX_BACKOFF_MS);
    }

    private void pause(long millis) throws IOException {
        try {
            sleeper.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while waiting to retry JEV request", e);
        }
    }

    private void awaitGate() throws IOException {
        if (gate == null) {
            return;
        }
        try {
            gate.awaitOpen(sleeper);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while waiting for the JEV rate limit to clear", e);
        }
    }

    private JevHttpResponse post(byte[] body) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) settings.getEndpoint().openConnection();
        try {
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(settings.getConnectTimeoutMs());
            conn.setReadTimeout(settings.getReadTimeoutMs());
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
            conn.setRequestProperty("Accept", "application/json");
            conn.setRequestProperty(settings.getAuthHeaderName(), settings.getAuthHeaderValue());
            try (OutputStream out = conn.getOutputStream()) {
                out.write(body);
            }
            int status = conn.getResponseCode();
            InputStream in = status >= 400 ? conn.getErrorStream() : conn.getInputStream();
            return new JevHttpResponse(status, readAll(in), conn.getHeaderField("Retry-After"));
        } finally {
            conn.disconnect();
        }
    }

    private static String readAll(InputStream in) throws IOException {
        if (in == null) {
            return "";
        }
        try (InputStream stream = in) {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            stream.transferTo(buffer);
            return buffer.toString(StandardCharsets.UTF_8);
        }
    }
}
