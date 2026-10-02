package com.boomi.custom.jev.review;

import com.boomi.connector.testutil.SimplePayloadMetadata;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.Test;

import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ResultPropertiesTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    // The result from the first real Boomi test run
    private static final String RESULT = "{\"status\":\"NEEDS_REVIEW\",\"model\":\"jev-1.13.0\",\"confidenceThreshold\":0.8,"
            + "\"results\":{"
            + "\"department\":{\"type\":\"choice\",\"value\":\"billing\",\"confidence\":0.99,\"passed\":true},"
            + "\"needs_human\":{\"type\":\"noul\",\"value\":true,\"probability\":0.73,\"confidence\":0.73,\"passed\":false},"
            + "\"urgency\":{\"type\":\"score\",\"value\":2.0,\"level\":\"Needs attention today\",\"confidence\":1.0,\"passed\":true}},"
            + "\"reviewReasons\":[\"needs_human: confidence 0.7300 below threshold 0.8000\"],"
            + "\"usage\":{\"input_tokens\":509,\"output_tokens\":93}}";

    private static JsonNode result() throws Exception {
        return MAPPER.readTree(RESULT);
    }

    @Test
    public void setsDocumentPropertiesForEveryAnswer() throws Exception {
        SimplePayloadMetadata md = new SimplePayloadMetadata();
        ResultProperties.apply(md, result(), true, false);
        Map<String, String> ddps = md.getUserDefProps();

        assertEquals("NEEDS_REVIEW", ddps.get("jevStatus"));
        assertEquals("jev-1.13.0", ddps.get("jevModel"));
        assertEquals("needs_human: confidence 0.7300 below threshold 0.8000", ddps.get("jevReviewReasons"));
        assertEquals("509", ddps.get("jevInputTokens"));
        assertEquals("billing", ddps.get("jev_department"));
        assertEquals("0.99", ddps.get("jev_department_confidence"));
        assertEquals("true", ddps.get("jev_needs_human"));
        assertEquals("false", ddps.get("jev_needs_human_passed"));
        assertEquals("2.0", ddps.get("jev_urgency"));
        assertEquals("Needs attention today", ddps.get("jev_urgency_level"));
        assertFalse(ddps.containsKey("jev_department_level"));
        assertTrue(md.getTrackedProps().isEmpty());
    }

    @Test
    public void setsTrackedPropertiesWithSummary() throws Exception {
        SimplePayloadMetadata md = new SimplePayloadMetadata();
        ResultProperties.apply(md, result(), false, true);
        Map<String, String> tracked = md.getTrackedProps();

        assertEquals("NEEDS_REVIEW", tracked.get("jevStatus"));
        assertEquals("department=billing; needs_human=true; urgency=Needs attention today", tracked.get("jevSummary"));
        assertEquals("93", tracked.get("jevOutputTokens"));
        assertTrue(md.getUserDefProps().isEmpty());
    }

    @Test
    public void truncatesLongTrackedValues() throws Exception {
        StringBuilder reasons = new StringBuilder();
        for (int i = 0; i < 200; i++) {
            reasons.append("\"reason number ").append(i).append("\",");
        }
        String json = "{\"status\":\"NEEDS_REVIEW\",\"results\":{},\"reviewReasons\":["
                + reasons.substring(0, reasons.length() - 1) + "]}";
        SimplePayloadMetadata md = new SimplePayloadMetadata();
        ResultProperties.apply(md, MAPPER.readTree(json), true, true);
        assertEquals(ResultProperties.MAX_TRACKED_LENGTH, md.getTrackedProps().get("jevReviewReasons").length());
        assertTrue(md.getUserDefProps().get("jevReviewReasons").length() > ResultProperties.MAX_TRACKED_LENGTH);
    }

    @Test
    public void errorPropertiesMarkStatusError() {
        SimplePayloadMetadata md = new SimplePayloadMetadata();
        ResultProperties.applyError(md, "422", true, true);
        assertEquals("ERROR", md.getUserDefProps().get("jevStatus"));
        assertEquals("422", md.getUserDefProps().get("jevErrorCode"));
        assertEquals("JEV error 422", md.getTrackedProps().get("jevSummary"));
    }
}
