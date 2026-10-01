package com.boomi.custom.jev.client;

import com.boomi.custom.jev.FakeJevServer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
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
}
