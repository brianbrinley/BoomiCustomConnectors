package com.boomi.connector.muse;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.LinkedHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import org.apache.http.Header;
import org.apache.http.HttpEntity;
import org.apache.http.HttpEntityEnclosingRequest;
import org.apache.http.HttpHost;
import org.apache.http.HttpRequest;
import org.apache.http.HttpVersion;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.message.BasicHttpResponse;
import org.apache.http.message.BasicStatusLine;
import org.apache.http.protocol.HttpContext;
import org.apache.http.util.EntityUtils;

import com.boomi.connector.api.ConnectorException;
import com.boomi.connector.api.ExtendedPayload;
import com.boomi.connector.api.ObjectData;
import com.boomi.connector.api.OperationContext;
import com.boomi.connector.api.OperationResponse;
import com.boomi.connector.api.OperationStatus;
import com.boomi.connector.api.Payload;
import com.boomi.connector.api.PayloadMetadata;
import com.boomi.connector.api.PropertyMap;
import com.boomi.connector.api.UpdateRequest;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Functional tests with a fake HTTP transport (no sockets — this sandbox
 * blocks Java TCP). Exercises the real retry loop, parsing, budgets, and
 * both operations end to end.
 */
public class MuseConnectorTest {

    static int passed = 0;
    static int failed = 0;

    // ---------- fake transport ----------

    static class Scripted {
        int status;
        String body;
        String retryAfter;
        IOException transportError;

        static Scripted ok(String body) {
            Scripted s = new Scripted();
            s.status = 200;
            s.body = body;
            return s;
        }

        static Scripted status(int status) {
            Scripted s = new Scripted();
            s.status = status;
            s.body = "{\"error\":{\"message\":\"boom\"}}";
            return s;
        }

        static Scripted transportError() {
            Scripted s = new Scripted();
            s.transportError = new IOException("connection reset");
            return s;
        }
    }

    static class Captured {
        String method;
        String uri;
        String auth;
        String body;
    }

    static class FakeHttp extends CloseableHttpClient {
        final Queue<Scripted> script = new ArrayDeque<>();
        final List<Captured> requests = new ArrayList<>();

        @Override
        protected CloseableHttpResponse doExecute(HttpHost target, HttpRequest request,
                HttpContext context) throws IOException {
            Captured cap = new Captured();
            cap.method = request.getRequestLine().getMethod();
            cap.uri = request.getRequestLine().getUri();
            Header h = request.getFirstHeader("Authorization");
            cap.auth = h == null ? null : h.getValue();
            if (request instanceof HttpEntityEnclosingRequest) {
                HttpEntity entity = ((HttpEntityEnclosingRequest) request).getEntity();
                cap.body = entity == null ? null
                        : EntityUtils.toString(entity, StandardCharsets.UTF_8);
            }
            requests.add(cap);

            Scripted s = script.poll();
            if (s == null) {
                throw new IOException("script exhausted after " + requests.size() + " requests");
            }
            if (s.transportError != null) {
                throw s.transportError;
            }
            final BasicHttpResponse real = new BasicHttpResponse(
                    new BasicStatusLine(HttpVersion.HTTP_1_1, s.status, ""));
            real.setEntity(new StringEntity(s.body == null ? "" : s.body, StandardCharsets.UTF_8));
            if (s.retryAfter != null) {
                real.setHeader("Retry-After", s.retryAfter);
            }
            return proxy(CloseableHttpResponse.class, (p, method, margs) -> {
                if (method.getName().equals("close")) {
                    return null;
                }
                return method.invoke(real, margs);
            });
        }

        @Override
        public void close() {
        }

        @Override
        @SuppressWarnings("deprecation")
        public org.apache.http.conn.ClientConnectionManager getConnectionManager() {
            return null;
        }

        @Override
        @SuppressWarnings("deprecation")
        public org.apache.http.params.HttpParams getParams() {
            return null;
        }
    }

    static final String OK_BODY = "{\"id\":\"chatcmpl-1\",\"model\":\"muse-spark-1.3\","
            + "\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"hello\"},\"finish_reason\":\"stop\"}],"
            + "\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":20,\"total_tokens\":30}}";

    static final String MODELS_BODY = "{\"data\":["
            + "{\"id\":\"muse-spark-1.3\",\"owned_by\":\"meta\"},"
            + "{\"id\":\"muse-spark-1.3-thinking\"}"
            + "]}";

    // ---------- proxies ----------

    @SuppressWarnings("unchecked")
    static <T> T proxy(Class<T> iface, InvocationHandler h) {
        return (T) Proxy.newProxyInstance(MuseConnectorTest.class.getClassLoader(),
                new Class<?>[] { iface }, h);
    }

    static Object defaultValue(Class<?> rt) {
        if (!rt.isPrimitive()) {
            return null;
        }
        if (rt == boolean.class) {
            return false;
        }
        return 0;
    }

    static PropertyMap props(Map<String, String> m) {
        return proxy(PropertyMap.class, (p, method, args) -> {
            String name = method.getName();
            if (name.equals("getProperty") && args.length >= 1) {
                String v = m.get(args[0]);
                if (v != null) {
                    return v;
                }
                return args.length == 2 ? args[1] : null;
            }
            if (name.equals("getBooleanProperty")) {
                String v = m.get(args[0]);
                if (v != null) {
                    return Boolean.parseBoolean(v);
                }
                return args.length == 2 ? args[1] : null;
            }
            if (name.equals("getLongProperty")) {
                String v = m.get(args[0]);
                if (v != null) {
                    return Long.parseLong(v);
                }
                return args.length == 2 ? args[1] : null;
            }
            return defaultValue(method.getReturnType());
        });
    }

    static OperationContext opContext(Map<String, String> connProps, Map<String, String> opProps,
            String customType, ConcurrentMap<Object, Object> cache) {
        PropertyMap cp = props(connProps);
        PropertyMap op = props(opProps);
        return proxy(OperationContext.class, (p, method, args) -> {
            switch (method.getName()) {
                case "getConnectionProperties":
                    return cp;
                case "getOperationProperties":
                    return op;
                case "getCustomOperationType":
                    return customType;
                case "getConnectorCache":
                    return cache;
                default:
                    return defaultValue(method.getReturnType());
            }
        });
    }

    static ObjectData doc(String json) {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        return proxy(ObjectData.class, (p, method, args) -> {
            switch (method.getName()) {
                case "getData":
                    return new ByteArrayInputStream(bytes);
                case "getLogger":
                    return Logger.getLogger("test");
                case "toString":
                    return "doc";
                case "hashCode":
                    return System.identityHashCode(p);
                case "equals":
                    return p == args[0];
                default:
                    return defaultValue(method.getReturnType());
            }
        });
    }

    static class Result {
        OperationStatus status;
        String code;
        String message;
        String payload;
        Payload rawPayload;
    }

    static UpdateRequest updateRequest(List<ObjectData> docs) {
        return proxy(UpdateRequest.class, (p, method, args) -> {
            if (method.getName().equals("iterator")) {
                return docs.iterator();
            }
            if (method.getName().equals("toString")) {
                return "updateRequest";
            }
            return defaultValue(method.getReturnType());
        });
    }

    /** Captures setTrackedProperty calls made on payload metadata. */
    static class RecordingMetadata {
        final Map<String, String> tracked = new LinkedHashMap<>();

        PayloadMetadata proxy() {
            return MuseConnectorTest.proxy(PayloadMetadata.class, (p, method, margs) -> {
                if (method.getName().equals("setTrackedProperty")) {
                    tracked.put((String) margs[0], (String) margs[1]);
                }
                return null;
            });
        }
    }

    static class RecordingResponse {
        final List<Result> results = new ArrayList<>();
        final RecordingMetadata metadata = new RecordingMetadata();

        OperationResponse proxy() {
            return MuseConnectorTest.proxy(OperationResponse.class, (p, method, args) -> {
                if (method.getName().equals("createMetadata")) {
                    return metadata.proxy();
                }
                if (method.getName().equals("addResult")) {
                    Result r = new Result();
                    r.status = (OperationStatus) args[1];
                    r.code = (String) args[2];
                    r.message = (String) args[3];
                    Payload payload = (Payload) args[4];
                    r.rawPayload = payload;
                    if (payload != null) {
                        // String payloads serve writeTo(), not readFrom().
                        ByteArrayOutputStream bos = new ByteArrayOutputStream();
                        payload.writeTo(bos);
                        r.payload = new String(bos.toByteArray(), StandardCharsets.UTF_8);
                    }
                    results.add(r);
                    return null;
                }
                if (method.getName().equals("getLogger")) {
                    return Logger.getLogger("test");
                }
                return defaultValue(method.getReturnType());
            });
        }
    }

    // ---------- harness ----------

    static class Harness {
        final FakeHttp http = new FakeHttp();
        final MuseConnector connector = new MuseConnector() {
            @Override
            CloseableHttpClient newPooledClient(MuseConnection conn) {
                return http;
            }
        };

        MuseConnection conn(Map<String, String> cp) {
            return MuseConnection.from(props(cp));
        }
    }

    static void check(boolean cond, String name) {
        if (cond) {
            passed++;
            System.out.println("PASS " + name);
        } else {
            failed++;
            System.out.println("FAIL " + name);
        }
    }

    static Map<String, String> connProps() {
        Map<String, String> m = new HashMap<>();
        m.put("apiKey", "test-key");
        m.put("baseUrl", "https://api.meta.ai/v1");
        m.put("model", "muse-spark-1.3");
        m.put("retryBackoffMs", "1");
        return m;
    }

    static ObjectNode emptyBody() {
        return MuseClient.MAPPER.createObjectNode();
    }

    // ---------- tests ----------

    public static void main(String[] args) throws Exception {
        testChatSuccess();
        testRetryOn429();
        testRetryExhausted();
        testNoRetryOn401();
        testRetryOn500();
        testRetryOnTransportError();
        testProbe();
        testBudgetMath();
        testConnectionParsing();
        testOperationHappyPath();
        testOperationBadInput();
        testBudgetShortCircuit();
        testBulkOrdering();
        testBulkAuthFatal();
        testListModels();
        testListModelsRetry();
        testListModelsAuth();
        testErrorMessageExtraction();
        testDryRun();
        testDryRunBulk();
        testListModelsOperation();
        testTrackedProperties();
        testDebugLogging();
        testDebugLoggingDefaultOff();

        System.out.println("\n" + passed + " passed, " + failed + " failed");
        if (failed > 0) {
            System.exit(1);
        }
    }

    static void testChatSuccess() throws Exception {
        Harness hz = new Harness();
        hz.http.script.add(Scripted.ok(OK_BODY));
        MuseConnection conn = hz.conn(connProps());
        MuseClient.ChatResult r = MuseClient.chat(hz.http, conn, emptyBody());
        check(hz.http.requests.size() == 1, "chat makes one call");
        Captured c = hz.http.requests.get(0);
        check("Bearer test-key".equals(c.auth), "chat sends bearer auth");
        check("POST".equals(c.method) && c.uri.equals("https://api.meta.ai/v1/chat/completions"),
                "chat posts to chat completions");
        check("hello".equals(r.content) && r.promptTokens == 10 && r.completionTokens == 20
                && "chatcmpl-1".equals(r.id) && "stop".equals(r.finishReason),
                "chat parses content, usage, finish reason");
    }

    static void testRetryOn429() throws Exception {
        Harness hz = new Harness();
        Map<String, String> m = connProps();
        m.put("maxRetries", "3");
        Scripted first = Scripted.status(429);
        first.retryAfter = "0";
        hz.http.script.add(first);
        hz.http.script.add(Scripted.ok(OK_BODY));
        MuseConnection conn = hz.conn(m);
        MuseClient.ChatResult r = MuseClient.chat(hz.http, conn, emptyBody());
        check(r != null && hz.http.requests.size() == 2, "429 retried once, then succeeds");
    }

    static void testRetryExhausted() {
        Harness hz = new Harness();
        Map<String, String> m = connProps();
        m.put("maxRetries", "2");
        hz.http.script.add(Scripted.status(429));
        hz.http.script.add(Scripted.status(429));
        hz.http.script.add(Scripted.status(429));
        hz.http.script.add(Scripted.ok(OK_BODY)); // must never be reached
        try {
            MuseClient.chat(hz.http, hz.conn(m), emptyBody());
            check(false, "exhausted retries raise");
        } catch (MuseApiException e) {
            check(e.getStatusCode() == 429 && hz.http.requests.size() == 3,
                    "gives up after 1 + 2 retries");
        } catch (Exception e) {
            check(false, "unexpected " + e);
        }
    }

    static void testNoRetryOn401() {
        Harness hz = new Harness();
        hz.http.script.add(Scripted.status(401));
        hz.http.script.add(Scripted.ok(OK_BODY));
        try {
            MuseClient.chat(hz.http, hz.conn(connProps()), emptyBody());
            check(false, "401 raises");
        } catch (MuseApiException e) {
            check(e.isAuthFailure() && hz.http.requests.size() == 1, "401 never retried");
        } catch (Exception e) {
            check(false, "unexpected " + e);
        }
    }

    static void testRetryOn500() throws Exception {
        Harness hz = new Harness();
        hz.http.script.add(Scripted.status(500));
        hz.http.script.add(Scripted.ok(OK_BODY));
        MuseClient.ChatResult r = MuseClient.chat(hz.http, hz.conn(connProps()), emptyBody());
        check(r != null && hz.http.requests.size() == 2, "500 retried, then succeeds");
    }

    static void testRetryOnTransportError() throws Exception {
        Harness hz = new Harness();
        hz.http.script.add(Scripted.transportError());
        hz.http.script.add(Scripted.ok(OK_BODY));
        MuseClient.ChatResult r = MuseClient.chat(hz.http, hz.conn(connProps()), emptyBody());
        check(r != null && hz.http.requests.size() == 2, "transport error retried, then succeeds");
    }

    static void testProbe() throws Exception {
        Harness hz = new Harness();
        hz.http.script.add(Scripted.ok("{\"data\":[]}"));
        MuseClient.probe(hz.http, hz.conn(connProps()));
        check(true, "probe passes on 200");

        hz.http.script.add(Scripted.status(401));
        try {
            MuseClient.probe(hz.http, hz.conn(connProps()));
            check(false, "probe 401 raises");
        } catch (MuseApiException e) {
            check(e.isAuthFailure(), "probe 401 is auth failure");
        }

        // The probe retries server errors like everything else (default 2
        // retries): queue all three attempts.
        hz.http.script.add(Scripted.status(500));
        hz.http.script.add(Scripted.status(500));
        hz.http.script.add(Scripted.status(500));
        MuseClient.probe(hz.http, hz.conn(connProps()));
        check(hz.http.requests.size() == 5, "probe retries 500s, still only auth fails");
    }

    static void testBudgetMath() {
        Map<String, String> m = connProps();
        m.put("budgetMaxCostUsd", "20.00");
        BudgetTracker t = new BudgetTracker(MuseConnection.from(props(m)));
        check(t.blockReason() == null, "budget starts clear");
        t.record(1_000_000L, 1_000_000L); // 4.00 + 20.00 = 24.00
        String reason = t.blockReason();
        check(reason != null && reason.contains("cost"), "budget trips on cost (" + reason + ")");
        check(t.summary().contains("1000000 input tokens"),
                "summary reports tokens (" + t.summary() + ")");
    }

    static void testConnectionParsing() {
        Map<String, String> m = connProps();
        MuseConnection c = MuseConnection.from(props(m));
        check(c.getBaseUrl().equals("https://api.meta.ai/v1")
                && c.getConnectTimeoutMs() == 10000
                && c.getBulkMaxConcurrency() == 8
                && c.getBudgetMaxCostUsd() == null,
                "connection defaults apply");

        Map<String, String> badKey = connProps();
        badKey.put("apiKey", "  ");
        try {
            MuseConnection.from(props(badKey));
            check(false, "blank api key rejected");
        } catch (IllegalArgumentException e) {
            check(true, "blank api key rejected");
        }

        Map<String, String> badUrl = connProps();
        badUrl.put("baseUrl", "not a url");
        try {
            MuseConnection.from(props(badUrl));
            check(false, "bad base url rejected");
        } catch (IllegalArgumentException e) {
            check(true, "bad base url rejected");
        }
    }

    static void testOperationHappyPath() {
        Harness hz = new Harness();
        hz.http.script.add(Scripted.ok(OK_BODY));
        hz.http.script.add(Scripted.ok(OK_BODY));
        OperationContext ctx = opContext(connProps(), new HashMap<>(), "chat", new ConcurrentHashMap<>());
        MuseExecuteOperation op = new MuseExecuteOperation(ctx, hz.connector);
        List<ObjectData> docs = Arrays.asList(
                doc("{\"prompt\":\"hi\",\"temperature\":0.5}"),
                doc("{\"messages\":[{\"role\":\"user\",\"content\":\"yo\"}]}"));
        RecordingResponse rr = new RecordingResponse();
        op.executeUpdate(updateRequest(docs), rr.proxy());

        check(rr.results.size() == 2, "two docs in, two results out");
        check(rr.results.stream().allMatch(r -> r.status == OperationStatus.SUCCESS), "both succeed");
        String p0 = rr.results.get(0).payload;
        check(p0.contains("\"content\":\"hello\"") && p0.contains("\"promptTokens\":10")
                && p0.contains("estimatedCostUsd"), "output carries content, usage, cost");
        check(hz.http.requests.get(0).body.contains("\"temperature\":0.5"),
                "doc temperature reaches the API");
        check(hz.http.requests.get(1).body.contains("\"messages\":[{\"role\":\"user\",\"content\":\"yo\"}]"),
                "doc messages pass through untouched");
    }

    static void testOperationBadInput() {
        Harness hz = new Harness();
        hz.http.script.add(Scripted.ok(OK_BODY));
        OperationContext ctx = opContext(connProps(), new HashMap<>(), "chat", new ConcurrentHashMap<>());
        MuseExecuteOperation op = new MuseExecuteOperation(ctx, hz.connector);
        RecordingResponse rr = new RecordingResponse();
        op.executeUpdate(updateRequest(Arrays.asList(doc("not json"), doc("{\"prompt\":\"fine\"}"))),
                rr.proxy());
        check(rr.results.size() == 2, "bad doc does not swallow its neighbor");
        check(rr.results.get(0).status == OperationStatus.APPLICATION_ERROR
                && "BAD_INPUT".equals(rr.results.get(0).code), "bad doc gets BAD_INPUT");
        check(rr.results.get(1).status == OperationStatus.SUCCESS, "good doc still succeeds");
    }

    static void testBudgetShortCircuit() {
        Harness hz = new Harness();
        hz.http.script.add(Scripted.ok(OK_BODY));
        Map<String, String> cp = connProps();
        cp.put("budgetMaxInputTokens", "10"); // mock reports 10 input tokens per call
        OperationContext ctx = opContext(cp, new HashMap<>(), "chat", new ConcurrentHashMap<>());
        MuseExecuteOperation op = new MuseExecuteOperation(ctx, hz.connector);
        RecordingResponse rr = new RecordingResponse();
        op.executeUpdate(
                updateRequest(Arrays.asList(doc("{\"prompt\":\"a\"}"), doc("{\"prompt\":\"b\"}"))),
                rr.proxy());
        check(rr.results.get(0).status == OperationStatus.SUCCESS,
                "first doc within budget succeeds");
        check(rr.results.get(1).status == OperationStatus.APPLICATION_ERROR
                && "BUDGET_EXCEEDED".equals(rr.results.get(1).code),
                "second doc short-circuits on budget");
        check(hz.http.requests.size() == 1, "no API call wasted after budget trips");
    }

    static void testBulkOrdering() {
        Harness hz = new Harness();
        for (int i = 0; i < 5; i++) {
            hz.http.script.add(Scripted.ok(OK_BODY));
        }
        Map<String, String> cp = connProps();
        cp.put("bulkMaxConcurrency", "3");
        OperationContext ctx = opContext(cp, new HashMap<>(), "bulkChat", new ConcurrentHashMap<>());
        MuseBulkChatOperation op = new MuseBulkChatOperation(ctx, hz.connector);
        List<ObjectData> docs = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            docs.add(doc("{\"prompt\":\"p" + i + "\"}"));
        }
        RecordingResponse rr = new RecordingResponse();
        op.executeUpdate(updateRequest(docs), rr.proxy());
        check(rr.results.size() == 5
                && rr.results.stream().allMatch(r -> r.status == OperationStatus.SUCCESS),
                "bulk: five docs in, five successes out");
    }

    static void testListModels() throws Exception {
        Harness hz = new Harness();
        hz.http.script.add(Scripted.ok(MODELS_BODY));
        List<MuseClient.ModelInfo> models = MuseClient.listModels(hz.http, hz.conn(connProps()));
        check(models.size() == 2, "two models parsed");
        check("muse-spark-1.3".equals(models.get(0).id)
                && "meta".equals(models.get(0).ownedBy)
                && models.get(1).ownedBy == null,
                "model id and owned_by parsed, missing owned_by is null");
        Captured c = hz.http.requests.get(0);
        check("GET".equals(c.method) && c.uri.equals("https://api.meta.ai/v1/models"),
                "list models GETs /models");
        check("Bearer test-key".equals(c.auth), "list models sends bearer auth");
    }

    static void testListModelsRetry() throws Exception {
        Harness hz = new Harness();
        hz.http.script.add(Scripted.status(429));
        hz.http.script.add(Scripted.ok(MODELS_BODY));
        List<MuseClient.ModelInfo> models = MuseClient.listModels(hz.http, hz.conn(connProps()));
        check(models.size() == 2 && hz.http.requests.size() == 2,
                "list models retries 429, then succeeds");
    }

    static void testListModelsAuth() {
        Harness hz = new Harness();
        hz.http.script.add(Scripted.status(401));
        try {
            MuseClient.listModels(hz.http, hz.conn(connProps()));
            check(false, "list models 401 raises");
        } catch (MuseApiException e) {
            check(e.isAuthFailure() && hz.http.requests.size() == 1,
                    "list models 401 is auth failure, never retried");
        } catch (Exception e) {
            check(false, "unexpected " + e);
        }
    }

    static void testErrorMessageExtraction() {
        Harness hz = new Harness();
        Map<String, String> m = connProps();
        m.put("maxRetries", "0");
        Scripted s = new Scripted();
        s.status = 400;
        s.body = "{\"error\":{\"message\":\"model 'nope' not found\"}}";
        hz.http.script.add(s);
        try {
            MuseClient.chat(hz.http, hz.conn(m), emptyBody());
            check(false, "400 raises");
        } catch (MuseApiException e) {
            check(e.getMessage().contains("model 'nope' not found")
                    && e.getMessage().startsWith("Muse API error 400:"),
                    "API error message extracted (" + e.getMessage() + ")");
        } catch (Exception e) {
            check(false, "unexpected " + e);
        }
    }

    static void testDryRun() {
        Harness hz = new Harness();
        // No script entries: any HTTP attempt throws "script exhausted".
        Map<String, String> op = new HashMap<>();
        op.put("dryRun", "true");
        OperationContext ctx = opContext(connProps(), op, "chat", new ConcurrentHashMap<>());
        MuseExecuteOperation operation = new MuseExecuteOperation(ctx, hz.connector);
        RecordingResponse rr = new RecordingResponse();
        operation.executeUpdate(updateRequest(Arrays.asList(doc("{\"prompt\":\"hi\"}"))),
                rr.proxy());
        check(rr.results.size() == 1 && rr.results.get(0).status == OperationStatus.SUCCESS,
                "dry run succeeds");
        check(hz.http.requests.isEmpty(), "dry run makes no HTTP call");
        String payload = rr.results.get(0).payload;
        check(payload.contains("\"model\":\"muse-spark-1.3\"")
                && payload.contains("\"content\":\"hi\""),
                "dry run returns the request body");
    }

    static void testDryRunBulk() {
        Harness hz = new Harness();
        Map<String, String> op = new HashMap<>();
        op.put("dryRun", "true");
        OperationContext ctx = opContext(connProps(), op, "bulkChat", new ConcurrentHashMap<>());
        MuseBulkChatOperation operation = new MuseBulkChatOperation(ctx, hz.connector);
        RecordingResponse rr = new RecordingResponse();
        operation.executeUpdate(
                updateRequest(Arrays.asList(doc("{\"prompt\":\"a\"}"), doc("{\"prompt\":\"b\"}"))),
                rr.proxy());
        check(rr.results.size() == 2
                && rr.results.stream().allMatch(r -> r.status == OperationStatus.SUCCESS),
                "bulk dry run succeeds per document");
        check(hz.http.requests.isEmpty(), "bulk dry run makes no HTTP calls");
    }

    static void testListModelsOperation() {
        Harness hz = new Harness();
        hz.http.script.add(Scripted.ok(MODELS_BODY));
        OperationContext ctx = opContext(connProps(), new HashMap<>(), "listModels",
                new ConcurrentHashMap<>());
        MuseListModelsOperation op = new MuseListModelsOperation(ctx, hz.connector);
        RecordingResponse rr = new RecordingResponse();
        op.executeUpdate(updateRequest(Arrays.asList(doc("{\"ignored\":true})"))), rr.proxy());
        check(rr.results.size() == 1 && rr.results.get(0).status == OperationStatus.SUCCESS,
                "list models op succeeds");
        String payload = rr.results.get(0).payload;
        check(payload.contains("\"models\":[")
                && payload.contains("\"id\":\"muse-spark-1.3\"")
                && payload.contains("\"ownedBy\":\"meta\""),
                "list models op returns the models array");
    }

    static void testTrackedProperties() {
        Harness hz = new Harness();
        hz.http.script.add(Scripted.ok(OK_BODY));
        OperationContext ctx = opContext(connProps(), new HashMap<>(), "chat", new ConcurrentHashMap<>());
        MuseExecuteOperation op = new MuseExecuteOperation(ctx, hz.connector);
        RecordingResponse rr = new RecordingResponse();
        op.executeUpdate(updateRequest(Arrays.asList(doc("{\"prompt\":\"hi\"}"))), rr.proxy());
        check(rr.results.get(0).status == OperationStatus.SUCCESS, "tracked props: success");
        check(rr.results.get(0).rawPayload instanceof ExtendedPayload,
                "tracked props: payload carries metadata");
        Map<String, String> t = rr.metadata.tracked;
        check("chatcmpl-1".equals(t.get("museResponseId")), "tracked: response id");
        check("muse-spark-1.3".equals(t.get("museModel")), "tracked: model");
        check("stop".equals(t.get("museFinishReason")), "tracked: finish reason");
        check("10".equals(t.get("musePromptTokens"))
                && "20".equals(t.get("museCompletionTokens"))
                && "30".equals(t.get("museTotalTokens")), "tracked: token counts");
        check(t.get("museEstimatedCostUsd") != null && !t.get("museEstimatedCostUsd").isEmpty(),
                "tracked: estimated cost (" + t.get("museEstimatedCostUsd") + ")");
    }

    static void testDebugLogging() throws Exception {
        Logger logger = Logger.getLogger("com.boomi.connector.muse.MuseClient");
        final List<String> messages = new ArrayList<>();
        Handler handler = new Handler() {
            @Override
            public void publish(LogRecord r) {
                messages.add(r.getMessage());
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        logger.addHandler(handler);
        Level old = logger.getLevel();
        logger.setLevel(Level.ALL);
        try {
            Harness hz = new Harness();
            hz.http.script.add(Scripted.ok(OK_BODY));
            Map<String, String> cp = connProps();
            cp.put("debugLogging", "true");
            MuseConnection conn = hz.conn(cp);
            check(conn.isDebugLogging(), "debug logging parses true");
            MuseClient.chat(hz.http, conn, emptyBody());
            check(messages.stream().anyMatch(m -> m.contains("promptTokens=10")
                    && m.contains("completionTokens=20")),
                    "debug logging writes a summary line");
            check(messages.stream().anyMatch(m -> m.startsWith("Muse chat request:")),
                    "debug logging writes the request body at FINE");
            check(messages.stream().anyMatch(m -> m.startsWith("Muse chat response:")),
                    "debug logging writes the response body at FINE");
        } finally {
            logger.removeHandler(handler);
            logger.setLevel(old);
        }
    }

    static void testDebugLoggingDefaultOff() {
        check(!MuseConnection.from(props(connProps())).isDebugLogging(),
                "debug logging defaults to off");
    }

    static void testBulkAuthFatal() {
        Harness hz = new Harness();
        hz.http.script.add(Scripted.status(401));
        OperationContext ctx = opContext(connProps(), new HashMap<>(), "bulkChat",
                new ConcurrentHashMap<>());
        MuseBulkChatOperation op = new MuseBulkChatOperation(ctx, hz.connector);
        RecordingResponse rr = new RecordingResponse();
        boolean threw = false;
        try {
            op.executeUpdate(updateRequest(Arrays.asList(doc("{\"prompt\":\"a\"}"))), rr.proxy());
        } catch (ConnectorException e) {
            threw = e.getMessage().contains("authentication failed");
        }
        check(threw, "bulk auth failure throws ConnectorException");
    }
}
