package com.boomi.custom.jev.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;

/**
 * Status code and body of one JEV HTTP call.
 */
public final class JevHttpResponse {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int MAX_MESSAGE_LENGTH = 500;

    private final int statusCode;
    private final String body;
    private final Integer retryAfterSeconds;

    public JevHttpResponse(int statusCode, String body) {
        this(statusCode, body, null);
    }

    public JevHttpResponse(int statusCode, String body, String retryAfterHeader) {
        this.statusCode = statusCode;
        this.body = body == null ? "" : body;
        this.retryAfterSeconds = parseRetryAfter(retryAfterHeader);
    }

    private static Integer parseRetryAfter(String header) {
        if (header == null) {
            return null;
        }
        try {
            int seconds = Integer.parseInt(header.trim());
            return seconds >= 0 ? seconds : null;
        } catch (NumberFormatException e) {
            // HTTP-date form is not supported; fall back to exponential backoff
            return null;
        }
    }

    /** Value of the Retry-After header in seconds, or null if absent or not numeric. */
    public Integer retryAfterSeconds() {
        return retryAfterSeconds;
    }

    public int getStatusCode() {
        return statusCode;
    }

    public String getBody() {
        return body;
    }

    public boolean isSuccess() {
        return statusCode >= 200 && statusCode < 300;
    }

    public JsonNode bodyAsJson() throws IOException {
        return MAPPER.readTree(body);
    }

    /**
     * Best-effort human readable error message from an error body. Handles {@code {"error":{"message":..}}},
     * {@code {"error":".."}} and {@code {"message":".."}}, falling back to the (truncated) raw body.
     */
    public String errorMessage() {
        try {
            JsonNode json = bodyAsJson();
            JsonNode error = json.path("error");
            if (error.isObject() && error.path("message").isTextual()) {
                return error.path("message").asText();
            }
            if (error.isTextual()) {
                return error.asText();
            }
            if (json.path("message").isTextual()) {
                return json.path("message").asText();
            }
        } catch (IOException | RuntimeException ignored) {
            // not JSON; fall through
        }
        String trimmed = body.trim();
        if (trimmed.isEmpty()) {
            return "HTTP " + statusCode;
        }
        return trimmed.length() > MAX_MESSAGE_LENGTH ? trimmed.substring(0, MAX_MESSAGE_LENGTH) + "..." : trimmed;
    }
}
