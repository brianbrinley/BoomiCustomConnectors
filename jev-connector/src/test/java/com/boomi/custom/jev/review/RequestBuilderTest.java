package com.boomi.custom.jev.review;

import com.boomi.custom.jev.JevTestData;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class RequestBuilderTest {

    private static QuestionSet questions() throws Exception {
        return QuestionSet.parse(JevTestData.QUESTION_SET);
    }

    @Test
    public void autoSendsJsonDocumentsAsJson() throws Exception {
        ObjectNode req = RequestBuilder.forReview("{\"message\":\"My payout failed\"}", StateFormat.AUTO, null,
                questions(), "jev-latest");
        assertEquals("jev-latest", req.path("model").asText());
        assertTrue(req.path("state").isObject());
        assertEquals("My payout failed", req.path("state").path("message").asText());
        assertEquals("choice", req.path("questions").path("department").path("type").asText());
    }

    @Test
    public void autoSendsPlainTextAsString() throws Exception {
        ObjectNode req = RequestBuilder.forReview("My payout failed", StateFormat.AUTO, null, questions(), "m");
        assertTrue(req.path("state").isTextual());
        assertEquals("My payout failed", req.path("state").asText());
    }

    @Test
    public void autoFallsBackToTextForMalformedJson() throws Exception {
        ObjectNode req = RequestBuilder.forReview("{not json", StateFormat.AUTO, null, questions(), "m");
        assertEquals("{not json", req.path("state").asText());
    }

    @Test
    public void textFormatKeepsJsonAsString() throws Exception {
        ObjectNode req = RequestBuilder.forReview("{\"a\":1}", StateFormat.TEXT, null, questions(), "m");
        assertTrue(req.path("state").isTextual());
    }

    @Test(expected = InvalidInputException.class)
    public void jsonFormatRejectsNonJson() throws Exception {
        RequestBuilder.forReview("plain text", StateFormat.JSON, null, questions(), "m");
    }

    @Test(expected = InvalidInputException.class)
    public void rejectsEmptyDocument() throws Exception {
        RequestBuilder.forReview("   ", StateFormat.AUTO, null, questions(), "m");
    }

    @Test
    public void wrapsStateUnderKey() throws Exception {
        ObjectNode req = RequestBuilder.forReview("hello", StateFormat.AUTO, "ticket", questions(), "m");
        assertEquals("hello", req.path("state").path("ticket").asText());
    }

    @Test
    public void rawRequestFillsMissingModel() throws Exception {
        String raw = "{\"state\":\"hi\",\"questions\":" + JevTestData.QUESTION_SET + "}";
        RequestBuilder.RawRequest r = RequestBuilder.fromRaw(raw, "jev-latest");
        assertEquals("jev-latest", r.getRequest().path("model").asText());
        assertEquals(2, r.getQuestions().getTypes().size());
    }

    @Test
    public void rawRequestKeepsExplicitModel() throws Exception {
        String raw = "{\"model\":\"jev-1.13\",\"state\":\"hi\",\"questions\":" + JevTestData.QUESTION_SET + "}";
        assertEquals("jev-1.13", RequestBuilder.fromRaw(raw, "jev-latest").getRequest().path("model").asText());
    }

    @Test
    public void rawRequestValidation() {
        assertRawRejected("[1]", "JSON object");
        assertRawRejected("{\"questions\":" + JevTestData.QUESTION_SET + "}", "state");
        assertRawRejected("{\"state\":\"x\"}", "non-empty");
    }

    private static void assertRawRejected(String raw, String messagePart) {
        try {
            RequestBuilder.fromRaw(raw, "m");
            fail("expected rejection of " + raw);
        } catch (InvalidInputException e) {
            assertTrue(e.getMessage(), e.getMessage().contains(messagePart));
        }
    }
}
