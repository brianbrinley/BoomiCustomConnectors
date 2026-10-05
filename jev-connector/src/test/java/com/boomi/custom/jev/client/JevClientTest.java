package com.boomi.custom.jev.client;

import com.boomi.custom.jev.FakeJevServer;
import com.boomi.custom.jev.concurrent.CallWindow;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class JevClientTest {

    private FakeJevServer server;
    private final List<Long> sleeps = new ArrayList<>();

    @Before
    public void start() throws IOException {
        server = new FakeJevServer();
    }

    @After
    public void stop() {
        server.close();
    }

    private JevClient client(String scheme, int retries) {
        JevSettings settings = new JevSettings(server.baseUrl() + "/", "v1/systemone", "secret", "Authorization",
                scheme, "jev-latest", 2000, 2000, retries);
        return new JevClient(settings, 100, sleeps::add);
    }

    private static ObjectNode body() {
        return new ObjectMapper().createObjectNode().put("state", "x");
    }

    @Test
    public void sendsJsonWithBearerAuth() throws Exception {
        server.enqueue(200, "{\"answers\":{}}");
        JevHttpResponse r = client("Bearer", 0).decide(body());
        assertTrue(r.isSuccess());
        FakeJevServer.Recorded req = server.getRequests().get(0);
        assertEquals("/v1/systemone", req.path);
        assertEquals("Bearer secret", req.header("Authorization"));
        assertTrue(req.header("Content-Type").startsWith("application/json"));
        assertEquals("{\"state\":\"x\"}", req.body);
    }

    @Test
    public void blankSchemeSendsRawKey() throws Exception {
        server.enqueue(200, "{}");
        client("", 0).decide(body());
        assertEquals("secret", server.getRequests().get(0).header("Authorization"));
    }

    @Test
    public void retriesRateLimitsWithBackoffAndRetryAfter() throws Exception {
        server.enqueue(429, "{}", "3").enqueue(503, "{}").enqueue(200, "{\"ok\":true}");
        JevHttpResponse r = client("Bearer", 2).decide(body());
        assertEquals(200, r.getStatusCode());
        assertEquals(3, server.getRequests().size());
        assertEquals(List.of(3000L, 200L), sleeps);
    }

    @Test
    public void returnsLastResponseWhenRetriesExhausted() throws Exception {
        server.enqueue(429, "{\"error\":{\"message\":\"slow down\"}}").enqueue(429, "{\"error\":{\"message\":\"slow down\"}}");
        JevHttpResponse r = client("Bearer", 1).decide(body());
        assertEquals(429, r.getStatusCode());
        assertEquals("slow down", r.errorMessage());
    }

    @Test
    public void doesNotRetryClientErrors() throws Exception {
        server.enqueue(401, "{\"message\":\"bad key\"}");
        JevHttpResponse r = client("Bearer", 3).decide(body());
        assertEquals(401, r.getStatusCode());
        assertEquals("bad key", r.errorMessage());
        assertEquals(1, server.getRequests().size());
    }

    @Test(expected = IOException.class)
    public void throwsWhenUnreachable() throws Exception {
        String url = server.baseUrl();
        server.close();
        JevSettings settings = new JevSettings(url, "/v1/systemone", "k", null, "Bearer", null, 500, 500, 1);
        new JevClient(settings, 1, sleeps::add).decide(body());
    }

    @Test
    public void rateLimitedCallClosesTheSharedGateForOtherCalls() throws Exception {
        AtomicLong now = new AtomicLong(10_000L);
        RateLimitGate gate = new RateLimitGate(now::get);
        JevSettings settings = new JevSettings(server.baseUrl(), "/v1/systemone", "secret", "Authorization",
                "Bearer", "jev-latest", 2000, 2000, 0);
        JevClient shared = new JevClient(settings, 100, millis -> {
            sleeps.add(millis);
            now.addAndGet(millis);
        }).withRateLimitGate(gate);

        server.enqueue(429, "{}", "3").enqueue(200, "{\"ok\":true}");
        assertEquals(429, shared.decide(body()).getStatusCode());
        assertTrue(sleeps.isEmpty());
        assertEquals(3000L, gate.remainingMillis());

        // the next call, from any worker, waits out the pause before it sends anything
        assertEquals(200, shared.decide(body()).getStatusCode());
        assertEquals(List.of(3000L), sleeps);
    }

    @Test
    public void retryingCallDoesNotWaitTwiceAtItsOwnGate() throws Exception {
        AtomicLong now = new AtomicLong();
        JevSettings settings = new JevSettings(server.baseUrl(), "/v1/systemone", "secret", "Authorization",
                "Bearer", "jev-latest", 2000, 2000, 1);
        JevClient gated = new JevClient(settings, 100, millis -> {
            sleeps.add(millis);
            now.addAndGet(millis);
        }).withRateLimitGate(new RateLimitGate(now::get));

        server.enqueue(429, "{}").enqueue(200, "{\"ok\":true}");
        assertEquals(200, gated.decide(body()).getStatusCode());
        assertEquals(List.of(100L), sleeps);
    }

    @Test
    public void serverErrorsDoNotCloseTheGate() throws Exception {
        RateLimitGate gate = new RateLimitGate();
        JevSettings settings = new JevSettings(server.baseUrl(), "/v1/systemone", "secret", "Authorization",
                "Bearer", "jev-latest", 2000, 2000, 0);
        server.enqueue(503, "{}");
        new JevClient(settings, 100, sleeps::add).withRateLimitGate(gate).decide(body());
        assertFalse(gate.remainingMillis() > 0);
    }

    @Test
    public void oneClientServesParallelWorkers() throws Exception {
        server.respondWith(request -> request.body).delay(100);
        JevSettings settings = new JevSettings(server.baseUrl(), "/v1/systemone", "secret", "Authorization",
                "Bearer", "jev-latest", 2000, 5000, 0);
        JevClient shared = new JevClient(settings).withRateLimitGate(new RateLimitGate());

        try (CallWindow calls = new CallWindow(4)) {
            for (int window = 0; window < 2; window++) {
                List<Callable<JevHttpResponse>> requests = new ArrayList<>();
                for (int i = 0; i < 4; i++) {
                    ObjectNode request = new ObjectMapper().createObjectNode().put("state", "doc-" + window + "-" + i);
                    requests.add(() -> shared.decide(request));
                }
                List<CallWindow.Outcome<JevHttpResponse>> outcomes = calls.run(requests);
                for (int i = 0; i < 4; i++) {
                    assertFalse(outcomes.get(i).failed());
                    assertEquals("{\"state\":\"doc-" + window + "-" + i + "\"}", outcomes.get(i).getValue().getBody());
                }
            }
        }
        assertEquals(8, server.getRequests().size());
        assertTrue("requests overlapped", server.maxInFlight() > 1);
        assertTrue("never more than the window", server.maxInFlight() <= 4);
    }
}
