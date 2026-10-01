package com.boomi.custom.jev;

import com.boomi.connector.api.ConnectionTester;
import com.boomi.connector.api.ConnectorException;
import com.boomi.connector.api.OperationStatus;
import com.boomi.connector.api.OperationType;
import com.boomi.connector.testutil.ConnectorTester;
import com.boomi.connector.testutil.MutableDynamicPropertyMap;
import com.boomi.connector.testutil.SimpleOperationResult;
import com.boomi.connector.testutil.SimpleTrackedData;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
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
                Collections.singletonMap(JevConstants.QUESTION_SET, SPAM_QUESTIONS));
        assertEquals(r.getMessage(), OperationStatus.SUCCESS, r.getStatus());
        assertTrue(MAPPER.readTree(server.getRequests().get(0).body).path("questions").has("spam"));
    }

    @Test
    public void connectorDocumentPropertyWinsOverDocumentPropertyAndOperation() throws Exception {
        server.enqueue(200, SPAM_RESPONSE);
        Map<String, String> ddps = Collections.singletonMap(JevConstants.QUESTION_SET,
                "{\"other\":{\"type\":\"noul\",\"instructions\":\"Other?\"}}");

        SimpleOperationResult r = executeWithProperties(reviewOperation(), ddps,
                Collections.singletonMap(JevConstants.QUESTION_SET, SPAM_QUESTIONS));
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
