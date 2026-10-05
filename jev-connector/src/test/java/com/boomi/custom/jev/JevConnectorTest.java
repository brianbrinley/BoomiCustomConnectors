package com.boomi.custom.jev;

import com.boomi.connector.api.ConnectionTester;
import com.boomi.connector.api.ConnectorException;
import com.boomi.connector.api.OperationStatus;
import com.boomi.connector.api.OperationType;
import com.boomi.connector.testutil.ConnectorTester;
import com.boomi.connector.testutil.MutableDynamicPropertyMap;
import com.boomi.connector.testutil.SimpleOperationResult;
import com.boomi.connector.testutil.SimplePayloadMetadata;
import com.boomi.connector.testutil.SimpleTrackedData;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/** End-to-end tests through the Boomi SDK test harness against a fake JEV server. */
public class JevConnectorTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private FakeJevServer server;
    private ConnectorTester tester;

    @Before
    public void setUp() throws Exception {
        server = new FakeJevServer();
        tester = new ConnectorTester(new JevConnector());
    }

    @After
    public void tearDown() {
        server.close();
    }

    private Map<String, Object> connection() {
        Map<String, Object> props = new HashMap<>();
        props.put(JevConstants.BASE_URL, server.baseUrl());
        props.put(JevConstants.ENDPOINT_PATH, "/v1/systemone");
        props.put(JevConstants.API_KEY, "test-key");
        props.put(JevConstants.AUTH_HEADER_NAME, "Authorization");
        props.put(JevConstants.AUTH_SCHEME, "Bearer");
        props.put(JevConstants.DEFAULT_MODEL, "jev-latest");
        props.put(JevConstants.MAX_RETRIES, 0L);
        return props;
    }

    private Map<String, Object> reviewOperation() {
        Map<String, Object> props = new HashMap<>();
        props.put(JevConstants.REQUEST_MODE, "DOCUMENT_REVIEW");
        props.put(JevConstants.QUESTION_SET, JevTestData.QUESTION_SET);
        props.put(JevConstants.STATE_FORMAT, "AUTO");
        props.put(JevConstants.CONFIDENCE_THRESHOLD, "0.8");
        props.put(JevConstants.INCLUDE_RAW_RESPONSE, false);
        props.put(JevConstants.MAX_DOCUMENT_SIZE_KB, 64L);
        return props;
    }

    private List<SimpleOperationResult> execute(Map<String, Object> op, InputStream... docs) {
        tester.setOperationContext(OperationType.EXECUTE, connection(), op, JevConstants.OBJECT_TYPE_REVIEW, null);
        return tester.executeExecuteOperation(Arrays.asList(docs));
    }

    private static InputStream doc(String text) {
        return new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8));
    }

    private static JsonNode payload(SimpleOperationResult result) throws Exception {
        return MAPPER.readTree(result.getPayloads().get(0));
    }

    @Test
    public void reviewsDocumentAndReturnsStructuredResult() throws Exception {
        server.enqueue(200, JevTestData.RESPONSE);
        List<SimpleOperationResult> results = execute(reviewOperation(), doc("{\"message\":\"My payout has failed three times.\"}"));

        assertEquals(1, results.size());
        SimpleOperationResult r = results.get(0);
        assertEquals(OperationStatus.SUCCESS, r.getStatus());
        assertEquals("200", r.getStatusCode());
        assertEquals("DECIDED", r.getMessage());
        JsonNode out = payload(r);
        assertEquals("billing", out.path("results").path("department").path("value").asText());

        FakeJevServer.Recorded sent = server.getRequests().get(0);
        assertEquals("Bearer test-key", sent.header("Authorization"));
        JsonNode body = MAPPER.readTree(sent.body);
        assertEquals("jev-latest", body.path("model").asText());
        assertEquals("My payout has failed three times.", body.path("state").path("message").asText());
        assertEquals("noul", body.path("questions").path("needs_human").path("type").asText());
    }

    @Test
    public void eachDocumentGetsItsOwnResult() throws Exception {
        server.enqueue(200, JevTestData.RESPONSE).enqueue(401, "{\"error\":{\"message\":\"invalid api key\"}}");
        List<SimpleOperationResult> results = execute(reviewOperation(),
                doc("first ticket"),
                new ByteArrayInputStream(new byte[] {0x25, 0x50, 0x44, 0x46, 0x00, 0x01}),
                doc("third ticket"));

        assertEquals(3, results.size());
        assertEquals(OperationStatus.SUCCESS, results.get(0).getStatus());

        assertEquals(OperationStatus.APPLICATION_ERROR, results.get(1).getStatus());
        assertEquals("INVALID_INPUT", results.get(1).getStatusCode());
        assertTrue(results.get(1).getMessage().contains("binary"));

        assertEquals(OperationStatus.APPLICATION_ERROR, results.get(2).getStatus());
        assertEquals("401", results.get(2).getStatusCode());
        assertEquals("invalid api key", results.get(2).getMessage());

        // the binary document never reached JEV
        assertEquals(2, server.getRequests().size());
    }

    @Test
    public void lowConfidenceIsFlaggedForReview() throws Exception {
        server.enqueue(200, JevTestData.RESPONSE);
        Map<String, Object> op = reviewOperation();
        op.put(JevConstants.CONFIDENCE_THRESHOLD, "0.95");
        SimpleOperationResult r = execute(op, doc("ticket")).get(0);
        assertEquals(OperationStatus.SUCCESS, r.getStatus());
        assertEquals("NEEDS_REVIEW", r.getMessage());
        assertEquals(2, payload(r).path("reviewReasons").size());
    }

    @Test
    public void rawRequestModePassesDocumentThrough() throws Exception {
        server.enqueue(200, JevTestData.RESPONSE);
        Map<String, Object> op = reviewOperation();
        op.put(JevConstants.REQUEST_MODE, "RAW_REQUEST");
        op.remove(JevConstants.QUESTION_SET);
        String raw = "{\"model\":\"jev-1.13\",\"state\":{\"message\":\"hi\"},\"questions\":" + JevTestData.QUESTION_SET + "}";

        SimpleOperationResult r = execute(op, doc(raw)).get(0);
        assertEquals(OperationStatus.SUCCESS, r.getStatus());
        assertEquals("jev-1.13", MAPPER.readTree(server.getRequests().get(0).body).path("model").asText());
    }

    @Test
    public void missingQuestionSetIsReportedPerDocument() {
        Map<String, Object> op = reviewOperation();
        op.remove(JevConstants.QUESTION_SET);
        SimpleOperationResult r = execute(op, doc("ticket")).get(0);
        assertEquals(OperationStatus.APPLICATION_ERROR, r.getStatus());
        assertEquals("INVALID_INPUT", r.getStatusCode());
        assertTrue(server.getRequests().isEmpty());
    }

    @Test
    public void dynamicOperationPropertiesOverrideQuestionsAndModel() throws Exception {
        server.enqueue(200, "{\"answers\":{\"spam\":{\"type\":\"noul\",\"noul\":0.02}}}");
        tester.setOperationContext(OperationType.EXECUTE, connection(), reviewOperation(),
                JevConstants.OBJECT_TYPE_REVIEW, null);
        MutableDynamicPropertyMap overrides = new MutableDynamicPropertyMap();
        overrides.addProperty(JevConstants.QUESTION_SET, "{\"spam\":{\"type\":\"noul\",\"instructions\":\"Is this spam?\"}}");
        overrides.addProperty(JevConstants.MODEL, "jev-1.13");
        SimpleTrackedData data = new SimpleTrackedData(1, doc("buy now!!!"), Collections.emptyMap(),
                Collections.emptyMap(), overrides);

        SimpleOperationResult r = tester.executeExecuteOperationWithTrackedData(Collections.singletonList(data)).get(0);
        assertEquals(OperationStatus.SUCCESS, r.getStatus());
        JsonNode sent = MAPPER.readTree(server.getRequests().get(0).body);
        assertEquals("jev-1.13", sent.path("model").asText());
        assertTrue(sent.path("questions").has("spam"));
        assertTrue(payload(r).path("results").path("spam").has("value"));
    }

    private static final String SPAM_QUESTIONS = "{\"spam\":{\"type\":\"noul\",\"instructions\":\"Is this spam?\"}}";
    private static final String SPAM_RESPONSE = "{\"answers\":{\"spam\":{\"type\":\"noul\",\"noul\":0.02}}}";

    private SimpleOperationResult executeWithProperties(Map<String, Object> op, Map<String, String> documentProps,
            Map<String, String> connectorProps) {
        tester.setOperationContext(OperationType.EXECUTE, connection(), op, JevConstants.OBJECT_TYPE_REVIEW, null);
        SimpleTrackedData data = new SimpleTrackedData(1, doc("buy now!!!"), documentProps, connectorProps);
        return tester.executeExecuteOperationWithTrackedData(Collections.singletonList(data)).get(0);
    }

    @Test
    public void dynamicDocumentPropertySuppliesQuestionSet() throws Exception {
        server.enqueue(200, SPAM_RESPONSE);
        Map<String, Object> op = reviewOperation();
        op.remove(JevConstants.QUESTION_SET);
        Map<String, String> ddps = new HashMap<>();
        ddps.put("QuestionSet", SPAM_QUESTIONS); // name match ignores case
        ddps.put("MODEL", "jev-from-ddp");

        SimpleOperationResult r = executeWithProperties(op, ddps, Collections.emptyMap());
        assertEquals(r.getMessage(), OperationStatus.SUCCESS, r.getStatus());
        JsonNode sent = MAPPER.readTree(server.getRequests().get(0).body);
        assertTrue(sent.path("questions").has("spam"));
        assertEquals("jev-from-ddp", sent.path("model").asText());
    }

    @Test
    public void connectorDocumentPropertySuppliesQuestionSet() throws Exception {
        server.enqueue(200, SPAM_RESPONSE);
        Map<String, Object> op = reviewOperation();
        op.remove(JevConstants.QUESTION_SET);

        SimpleOperationResult r = executeWithProperties(op, Collections.emptyMap(),
                Collections.singletonMap(JevConstants.DOC_PROP_QUESTION_SET, SPAM_QUESTIONS));
        assertEquals(r.getMessage(), OperationStatus.SUCCESS, r.getStatus());
        assertTrue(MAPPER.readTree(server.getRequests().get(0).body).path("questions").has("spam"));
    }

    @Test
    public void connectorDocumentPropertyWinsOverDocumentPropertyAndOperation() throws Exception {
        server.enqueue(200, SPAM_RESPONSE);
        Map<String, String> ddps = Collections.singletonMap(JevConstants.QUESTION_SET,
                "{\"other\":{\"type\":\"noul\",\"instructions\":\"Other?\"}}");

        SimpleOperationResult r = executeWithProperties(reviewOperation(), ddps,
                Collections.singletonMap(JevConstants.DOC_PROP_QUESTION_SET, SPAM_QUESTIONS));
        assertEquals(r.getMessage(), OperationStatus.SUCCESS, r.getStatus());
        JsonNode questions = MAPPER.readTree(server.getRequests().get(0).body).path("questions");
        assertTrue(questions.has("spam"));
        assertEquals(1, questions.size());
    }

    @Test
    public void missingQuestionSetErrorExplainsWhereToSetIt() {
        Map<String, Object> op = reviewOperation();
        op.remove(JevConstants.QUESTION_SET);
        SimpleOperationResult r = executeWithProperties(op, Collections.emptyMap(), Collections.emptyMap());
        assertEquals(OperationStatus.APPLICATION_ERROR, r.getStatus());
        assertTrue(r.getMessage(), r.getMessage().contains("Dynamic Operation Properties"));
        assertTrue(r.getMessage(), r.getMessage().contains("questionSet"));
        assertTrue(r.getMessage(), r.getMessage().contains("operation field: not set"));
        assertTrue(r.getMessage(), r.getMessage().contains("dynamic document properties present: none"));
    }

    @Test
    public void unsetConnectorDocumentPropertyDoesNotHideOperationQuestionSet() throws Exception {
        // Boomi may deliver declared-but-unset connector document properties as empty strings
        server.enqueue(200, JevTestData.RESPONSE);
        Map<String, String> connectorProps = new HashMap<>();
        connectorProps.put(JevConstants.DOC_PROP_QUESTION_SET, "");
        connectorProps.put(JevConstants.DOC_PROP_MODEL, "");
        connectorProps.put(JevConstants.DOC_PROP_CONFIDENCE_THRESHOLD, "");
        Map<String, String> ddps = Collections.singletonMap("someOtherProperty", "x");

        SimpleOperationResult r = executeWithProperties(reviewOperation(), ddps, connectorProps);
        assertEquals(r.getMessage(), OperationStatus.SUCCESS, r.getStatus());
        JsonNode sent = MAPPER.readTree(server.getRequests().get(0).body);
        assertTrue(sent.path("questions").has("department"));
        assertEquals("jev-latest", sent.path("model").asText());
    }

    @Test
    public void missingQuestionSetErrorListsDocumentPropertyNames() {
        Map<String, Object> op = reviewOperation();
        op.remove(JevConstants.QUESTION_SET);
        SimpleOperationResult r = executeWithProperties(op, Collections.singletonMap("DDP_QUESTIONS", "{}"),
                Collections.emptyMap());
        assertEquals(OperationStatus.APPLICATION_ERROR, r.getStatus());
        assertTrue(r.getMessage(), r.getMessage().contains("[DDP_QUESTIONS]"));
    }

    @Test
    public void propertiesAreOffByDefault() throws Exception {
        server.enqueue(200, JevTestData.RESPONSE);
        SimpleOperationResult r = execute(reviewOperation(), doc("My payout has failed three times.")).get(0);
        assertEquals(OperationStatus.SUCCESS, r.getStatus());
        for (SimplePayloadMetadata md : r.getPayloadMetadatas()) {
            assertTrue(md.getUserDefProps().isEmpty());
            assertTrue(md.getTrackedProps().isEmpty());
        }
    }

    @Test
    public void featureFlagsSetDocumentAndTrackedProperties() throws Exception {
        server.enqueue(200, JevTestData.RESPONSE);
        Map<String, Object> op = reviewOperation();
        op.put(JevConstants.SET_DOCUMENT_PROPERTIES, true);
        op.put(JevConstants.KEEP_ORIGINAL_DOCUMENT, true);
        op.put(JevConstants.SET_TRACKED_PROPERTIES, true);

        SimpleOperationResult r = execute(op, doc("My payout has failed three times.")).get(0);
        assertEquals(OperationStatus.SUCCESS, r.getStatus());
        SimplePayloadMetadata md = r.getPayloadMetadatas().get(0);
        assertEquals("DECIDED", md.getUserDefProps().get("jevStatus"));
        assertEquals("billing", md.getUserDefProps().get("jev_department"));
        assertEquals("My payout has failed three times.", md.getUserDefProps().get("jevOriginalDocument"));
        assertEquals("DECIDED", md.getTrackedProps().get("jevStatus"));
        assertEquals("department=billing; needs_human=true", md.getTrackedProps().get("jevSummary"));
    }

    @Test
    public void keepOriginalDocumentNeedsDocumentProperties() throws Exception {
        server.enqueue(200, JevTestData.RESPONSE);
        Map<String, Object> op = reviewOperation();
        op.put(JevConstants.KEEP_ORIGINAL_DOCUMENT, true);
        op.put(JevConstants.SET_TRACKED_PROPERTIES, true);

        SimplePayloadMetadata md = execute(op, doc("ticket")).get(0).getPayloadMetadatas().get(0);
        assertTrue(md.getUserDefProps().isEmpty());
        assertEquals("DECIDED", md.getTrackedProps().get("jevStatus"));
    }

    @Test
    public void jevErrorsAreMarkedWhenFlagsAreOn() throws Exception {
        server.enqueue(422, "{\"detail\":[{\"msg\":\"Field required\"}]}");
        Map<String, Object> op = reviewOperation();
        op.put(JevConstants.SET_DOCUMENT_PROPERTIES, true);

        SimpleOperationResult r = execute(op, doc("ticket")).get(0);
        assertEquals(OperationStatus.APPLICATION_ERROR, r.getStatus());
        SimplePayloadMetadata md = r.getPayloadMetadatas().get(0);
        assertEquals("ERROR", md.getUserDefProps().get("jevStatus"));
        assertEquals("422", md.getUserDefProps().get("jevErrorCode"));
    }

    // ---- Max Concurrent Requests ----

    private static final String GROUP_QUESTIONS = "{\"group\":{\"type\":\"choice\",\"instructions\":\"Which group?\","
            + "\"criteria\":{\"a\":\"First group\",\"b\":\"Second group\"}}}";

    /** Answers from the request itself, so parallel requests can arrive in any order. */
    private static String groupAnswer(FakeJevServer.Recorded request) {
        if (request.body.contains("broken")) {
            return "not json";
        }
        String group = request.body.contains("alpha") ? "a" : "b";
        return "{\"answers\":{\"group\":{\"choice\":\"" + group + "\",\"confidence\":0.99}}}";
    }

    private List<SimpleOperationResult> executeGrouping(Long maxConcurrentRequests, int documents) {
        server.respondWith(JevConnectorTest::groupAnswer).delay(60);
        Map<String, Object> op = reviewOperation();
        op.put(JevConstants.QUESTION_SET, GROUP_QUESTIONS);
        if (maxConcurrentRequests != null) {
            op.put(JevConstants.MAX_CONCURRENT_REQUESTS, maxConcurrentRequests);
        }
        List<InputStream> docs = new ArrayList<>();
        for (int i = 0; i < documents; i++) {
            docs.add(doc((i % 3 == 0 ? "alpha " : "beta ") + i));
        }
        return execute(op, docs.toArray(new InputStream[0]));
    }

    private static void assertGroupedInOrder(List<SimpleOperationResult> results, int documents) throws Exception {
        assertEquals(documents, results.size());
        for (int i = 0; i < documents; i++) {
            assertEquals(OperationStatus.SUCCESS, results.get(i).getStatus());
            assertEquals("document " + i, i % 3 == 0 ? "a" : "b",
                    payload(results.get(i)).path("results").path("group").path("value").asText());
        }
    }

    @Test
    public void requestsRunOneAtATimeByDefault() throws Exception {
        assertGroupedInOrder(executeGrouping(null, 5), 5);
        assertEquals(1, server.maxInFlight());
    }

    @Test
    public void concurrentRequestsKeepDocumentOrder() throws Exception {
        assertGroupedInOrder(executeGrouping(4L, 10), 10);
        assertEquals(10, server.getRequests().size());
        assertTrue("requests overlapped", server.maxInFlight() > 1);
        assertTrue("never more than Max Concurrent Requests", server.maxInFlight() <= 4);
    }

    @Test
    public void concurrentRequestsAreCapped() throws Exception {
        assertGroupedInOrder(executeGrouping(500L, 40), 40);
        assertTrue("capped at 16, saw " + server.maxInFlight(), server.maxInFlight() <= 16);
    }

    @Test
    public void concurrentFailuresStayWithTheirDocument() throws Exception {
        server.respondWith(JevConnectorTest::groupAnswer).delay(30);
        Map<String, Object> op = reviewOperation();
        op.put(JevConstants.QUESTION_SET, GROUP_QUESTIONS);
        op.put(JevConstants.MAX_CONCURRENT_REQUESTS, 3L);
        List<SimpleOperationResult> results = execute(op,
                doc("alpha"),
                new ByteArrayInputStream(new byte[] {0x25, 0x50, 0x44, 0x46, 0x00, 0x01}),
                doc("beta broken"),
                doc("alpha"),
                doc("beta"));

        assertEquals(5, results.size());
        assertEquals(OperationStatus.SUCCESS, results.get(0).getStatus());
        assertEquals("INVALID_INPUT", results.get(1).getStatusCode());
        assertEquals("INVALID_RESPONSE", results.get(2).getStatusCode());
        assertEquals(OperationStatus.SUCCESS, results.get(3).getStatus());
        assertEquals("b", payload(results.get(4)).path("results").path("group").path("value").asText());
        // the binary document never reached JEV
        assertEquals(4, server.getRequests().size());
    }

    @Test
    public void browseGeneratesTypedResponseProfile() throws Exception {
        tester.setBrowseContext(OperationType.EXECUTE, connection(), reviewOperation());
        String types = tester.browseTypes();
        assertTrue(types, types.contains(JevConstants.OBJECT_TYPE_REVIEW));

        String profiles = tester.browseProfiles(JevConstants.OBJECT_TYPE_REVIEW);
        assertTrue(profiles, profiles.contains("department"));
        assertTrue(profiles, profiles.contains("needs_human"));
    }

    @Test
    public void testConnectionSucceeds() throws Exception {
        server.enqueue(200, "{\"answers\":{\"connection_test\":{\"type\":\"noul\",\"noul\":0.99}}}");
        tester.setBrowseContext(OperationType.EXECUTE, connection(), new HashMap<>());
        ((ConnectionTester) tester.getConnector().createBrowser(tester.getBrowseContext())).testConnection();
        assertEquals("connection_test",
                MAPPER.readTree(server.getRequests().get(0).body).path("questions").fieldNames().next());
    }

    @Test
    public void testConnectionReportsAuthFailure() {
        server.enqueue(401, "{\"error\":{\"message\":\"invalid api key\"}}");
        tester.setBrowseContext(OperationType.EXECUTE, connection(), new HashMap<>());
        try {
            ((ConnectionTester) tester.getConnector().createBrowser(tester.getBrowseContext())).testConnection();
            fail("expected failure");
        } catch (ConnectorException e) {
            assertEquals("401", e.getStatusCode());
            assertTrue(e.getMessage(), e.getMessage().contains("invalid api key"));
        }
    }
}
