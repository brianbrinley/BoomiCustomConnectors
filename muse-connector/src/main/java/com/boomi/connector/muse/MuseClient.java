package com.boomi.connector.muse;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.apache.http.Header;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.methods.HttpRequestBase;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.impl.conn.PoolingHttpClientConnectionManager;
import org.apache.http.util.EntityUtils;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Speaks the Meta Model API's OpenAI-compatible surface. Stateless and
 * thread-safe: the pooled {@link CloseableHttpClient} is supplied by the
 * caller, and every request carries its own timeouts and credentials.
 *
 * Responses are read fully into memory. That is deliberate, not lazy: a chat
 * completion is bounded by the model's max output tokens, so the payload
 * cannot grow without bound the way a paged query can.
 */
public final class MuseClient {

    private static final Logger LOG = Logger.getLogger(MuseClient.class.getName());

    static final ObjectMapper MAPPER = new ObjectMapper();

    private MuseClient() {
    }

    /** One parsed chat completion. */
    public static final class ChatResult {
        public final String id;
        public final String model;
        public final String content;
        public final String finishReason;
        public final long promptTokens;
        public final long completionTokens;

        ChatResult(String id, String model, String content, String finishReason,
                long promptTokens, long completionTokens) {
            this.id = id;
            this.model = model;
            this.content = content;
            this.finishReason = finishReason;
            this.promptTokens = promptTokens;
            this.completionTokens = completionTokens;
        }

        public long totalTokens() {
            return promptTokens + completionTokens;
        }
    }

    /** One model entry from GET /models. */
    public static final class ModelInfo {
        public final String id;
        public final String ownedBy;

        ModelInfo(String id, String ownedBy) {
            this.id = id;
            this.ownedBy = ownedBy;
        }
    }

    /**
     * Builds a pooled client. Expired and idle connections are evicted in the
     * background so the pool does not accumulate dead sockets.
     */
    public static CloseableHttpClient newPooledClient(MuseConnection conn) {
        PoolingHttpClientConnectionManager pool = new PoolingHttpClientConnectionManager();
        pool.setMaxTotal(conn.getMaxConnectionsTotal());
        pool.setDefaultMaxPerRoute(conn.getMaxConnectionsPerRoute());
        return HttpClients.custom()
                .setConnectionManager(pool)
                .evictExpiredConnections()
                .evictIdleConnections(30L, TimeUnit.SECONDS)
                .disableCookieManagement()
                .build();
    }

    private static RequestConfig requestConfig(MuseConnection conn) {
        return RequestConfig.custom()
                .setConnectTimeout(conn.getConnectTimeoutMs())
                .setSocketTimeout(conn.getReadTimeoutMs())
                .setConnectionRequestTimeout(conn.getConnectTimeoutMs())
                .build();
    }

    /** Builds one HTTP request; invoked fresh on every attempt. */
    private interface RequestFactory {
        HttpRequestBase build();
    }

    /** One completed HTTP exchange. */
    private static final class Exchange {
        final int status;
        final String payload;
        final Header retryAfter;

        Exchange(int status, String payload, Header retryAfter) {
            this.status = status;
            this.payload = payload;
            this.retryAfter = retryAfter;
        }
    }

    /**
     * Runs one request, retrying rate limits, server errors and transport
     * failures. A 401/403 is never retried: the key is wrong and trying
     * again cannot help.
     */
    private static Exchange executeWithRetry(CloseableHttpClient http, MuseConnection conn,
            RequestFactory factory) throws IOException {
        int attempt = 0;
        while (true) {
            attempt++;
            HttpRequestBase request = factory.build();
            request.setConfig(requestConfig(conn));
            request.setHeader("Authorization", "Bearer " + conn.getApiKey());
            request.setHeader("Accept", "application/json");

            try (CloseableHttpResponse response = http.execute(request)) {
                int status = response.getStatusLine().getStatusCode();
                Header retryAfter = response.getFirstHeader("Retry-After");
                String payload = response.getEntity() == null
                        ? ""
                        : EntityUtils.toString(response.getEntity(), StandardCharsets.UTF_8);
                if ((status == 429 || (status >= 500 && status < 600))
                        && attempt <= conn.getMaxRetries()) {
                    sleepQuietly(backoffMs(conn, attempt, retryAfter));
                    continue;
                }
                return new Exchange(status, payload, retryAfter);
            } catch (IOException e) {
                // Usually a connect timeout: nothing was sent, so retrying is safe.
                if (attempt <= conn.getMaxRetries()) {
                    LOG.log(Level.FINE, "Muse request transport failure, retrying", e);
                    sleepQuietly(backoffMs(conn, attempt, null));
                    continue;
                }
                throw e;
            }
        }
    }

    /**
     * Sends one chat completion request, retrying rate limits and server
     * errors.
     */
    public static ChatResult chat(CloseableHttpClient http, MuseConnection conn, final ObjectNode body)
            throws IOException, MuseApiException {
        final String url = conn.getBaseUrl() + "/chat/completions";
        Exchange ex = executeWithRetry(http, conn, new RequestFactory() {
            @Override
            public HttpRequestBase build() {
                HttpPost post = new HttpPost(url);
                post.setHeader("Content-Type", "application/json");
                post.setEntity(new StringEntity(body.toString(), StandardCharsets.UTF_8));
                return post;
            }
        });
        if (ex.status == 200) {
            ChatResult result = parseResult(ex.payload);
            if (conn.isDebugLogging()) {
                LOG.info("Muse chat: model=" + result.model + " status=200"
                        + " promptTokens=" + result.promptTokens
                        + " completionTokens=" + result.completionTokens);
                LOG.fine("Muse chat request: " + body.toString());
                LOG.fine("Muse chat response: " + ex.payload);
            }
            return result;
        }
        String message = errorMessage(ex.status, ex.payload);
        if (conn.isDebugLogging()) {
            LOG.info("Muse chat: model=" + body.path("model").asText("?")
                    + " status=" + ex.status + " " + message);
        }
        throw new MuseApiException(ex.status, message);
    }

    /**
     * Lists the models the API key can reach. Retries like {@link #chat};
     * callers treat 401/403 as an authentication failure.
     */
    public static List<ModelInfo> listModels(CloseableHttpClient http, MuseConnection conn)
            throws IOException, MuseApiException {
        final String url = conn.getBaseUrl() + "/models";
        Exchange ex = executeWithRetry(http, conn, new RequestFactory() {
            @Override
            public HttpRequestBase build() {
                return new HttpGet(url);
            }
        });
        if (ex.status == 401 || ex.status == 403) {
            throw new MuseApiException(ex.status,
                    "The API key was rejected (HTTP " + ex.status
                            + "). Check the key in the connection.");
        }
        if (ex.status != 200) {
            throw new MuseApiException(ex.status, errorMessage(ex.status, ex.payload));
        }
        final JsonNode root;
        try {
            root = MAPPER.readTree(ex.payload);
        } catch (IOException e) {
            throw new MuseApiException("The Muse API returned a response that is not JSON.", e);
        }
        List<ModelInfo> models = new ArrayList<>();
        JsonNode data = root.path("data");
        if (data.isArray()) {
            for (JsonNode m : data) {
                String id = m.path("id").asText(null);
                if (id != null) {
                    models.add(new ModelInfo(id, m.path("owned_by").asText(null)));
                }
            }
        }
        if (conn.isDebugLogging()) {
            LOG.info("Muse list models: status=200 models=" + models.size());
            LOG.fine("Muse list models response: " + ex.payload);
        }
        return models;
    }

    /**
     * Side-effect-free health check used by Test Connection. Only an
     * authentication refusal fails; anything else — including an unexpected
     * shape — passes, because the probe's job is to validate the key, not the
     * API's mood.
     */
    public static void probe(CloseableHttpClient http, MuseConnection conn)
            throws IOException, MuseApiException {
        final String url = conn.getBaseUrl() + "/models";
        Exchange ex = executeWithRetry(http, conn, new RequestFactory() {
            @Override
            public HttpRequestBase build() {
                return new HttpGet(url);
            }
        });
        if (ex.status == 401 || ex.status == 403) {
            throw new MuseApiException(ex.status,
                    "The API key was rejected (HTTP " + ex.status + "). Check the key in the connection.");
        }
        if (conn.isDebugLogging()) {
            LOG.fine("Muse test connection: status=" + ex.status);
        }
    }

    private static ChatResult parseResult(String payload) throws MuseApiException {
        final JsonNode root;
        try {
            root = MAPPER.readTree(payload);
        } catch (IOException e) {
            throw new MuseApiException("The Muse API returned a response that is not JSON.", e);
        }
        JsonNode error = root.get("error");
        if (error != null && !error.isNull()) {
            throw new MuseApiException(200, "Muse API error: " + error.path("message").asText(error.toString()));
        }
        JsonNode choice = root.path("choices").path(0);
        JsonNode message = choice.path("message");
        String content = message.path("content").isMissingNode() ? null : message.path("content").asText(null);
        JsonNode usage = root.path("usage");
        return new ChatResult(
                root.path("id").asText(null),
                root.path("model").asText(null),
                content,
                choice.path("finish_reason").asText(null),
                usage.path("prompt_tokens").asLong(0L),
                usage.path("completion_tokens").asLong(0L));
    }

    /**
     * Pulls the human message out of an API error body, so failures say what
     * actually went wrong instead of dumping raw JSON. Falls back to a
     * trimmed excerpt when the body is not JSON or carries no message.
     */
    private static String errorMessage(int status, String payload) {
        String detail = null;
        if (payload != null && !payload.isEmpty()) {
            try {
                JsonNode error = MAPPER.readTree(payload).path("error");
                if (error.isTextual()) {
                    detail = error.asText();
                } else if (error.isObject()) {
                    detail = error.path("message").asText(null);
                    if (detail == null) {
                        detail = error.toString();
                    }
                }
            } catch (IOException e) {
                // Not JSON; fall through to the excerpt.
            }
        }
        if (detail == null || detail.isEmpty()) {
            detail = excerpt(payload);
        }
        return "Muse API error " + status + ": " + detail;
    }

    private static long backoffMs(MuseConnection conn, int attempt, Header retryAfter) {
        if (retryAfter != null) {
            try {
                return Math.max(0L, Long.parseLong(retryAfter.getValue().trim()) * 1000L);
            } catch (NumberFormatException e) {
                // fall through to exponential backoff
            }
        }
        long base = conn.getRetryBackoffMs() * (1L << (attempt - 1));
        // +/-20% jitter so a fleet of Atoms does not retry in lockstep.
        double jitter = 0.8 + 0.4 * ThreadLocalRandom.current().nextDouble();
        return (long) (base * jitter);
    }

    private static void sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static String excerpt(String payload) {
        if (payload == null || payload.isEmpty()) {
            return "(empty response body)";
        }
        String oneLine = payload.replaceAll("\\s+", " ").trim();
        return oneLine.length() > 300 ? oneLine.substring(0, 300) + "..." : oneLine;
    }
}
