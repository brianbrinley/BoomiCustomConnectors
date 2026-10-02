package com.boomi.custom.jev.review;

import com.boomi.custom.jev.JevTestData;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class ResultMapperTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static ObjectNode map(String response, Double threshold, boolean raw) throws Exception {
        return ResultMapper.map(MAPPER.readTree(response), QuestionSet.parse(JevTestData.QUESTION_SET), threshold, raw);
    }

    @Test
    public void mapsPublishedExampleAsDecided() throws Exception {
        ObjectNode out = map(JevTestData.RESPONSE, 0.8, false);
        assertEquals("DECIDED", out.path("status").asText());
        assertEquals("jev-1.13.0", out.path("model").asText());

        JsonNode dept = out.path("results").path("department");
        assertEquals("billing", dept.path("value").asText());
        assertEquals(0.92, dept.path("confidence").asDouble(), 1e-9);
        assertEquals(0.94, dept.path("probabilities").path("billing").asDouble(), 1e-9);
        assertTrue(dept.path("passed").asBoolean());

        JsonNode human = out.path("results").path("needs_human");
        assertTrue(human.path("value").asBoolean());
        assertEquals(0.87, human.path("probability").asDouble(), 1e-9);
        assertEquals(0.87, human.path("confidence").asDouble(), 1e-9);

        assertEquals(0, out.path("reviewReasons").size());
        assertEquals(180, out.path("usage").path("input_tokens").asInt());
        assertFalse(out.has("raw"));
    }

    @Test
    public void lowConfidenceNeedsReview() throws Exception {
        ObjectNode out = map(JevTestData.RESPONSE, 0.9, true);
        assertEquals("NEEDS_REVIEW", out.path("status").asText());
        assertFalse(out.path("results").path("needs_human").path("passed").asBoolean());
        assertTrue(out.path("results").path("department").path("passed").asBoolean());
        assertEquals(1, out.path("reviewReasons").size());
        assertTrue(out.path("reviewReasons").get(0).asText().startsWith("needs_human"));
        assertTrue(out.has("raw"));
    }

    @Test
    public void noThresholdMeansNoGating() throws Exception {
        ObjectNode out = map(JevTestData.RESPONSE, null, false);
        assertEquals("DECIDED", out.path("status").asText());
        assertTrue(out.path("confidenceThreshold").isNull());
    }

    @Test
    public void noulBelowHalfIsFalseWithSymmetricConfidence() throws Exception {
        String resp = "{\"answers\":{\"department\":{\"choice\":\"sales\",\"probabilities\":{\"sales\":0.85}},"
                + "\"needs_human\":{\"noul\":0.1}}}";
        ObjectNode out = map(resp, 0.8, false);
        JsonNode human = out.path("results").path("needs_human");
        assertFalse(human.path("value").asBoolean());
        assertEquals(0.9, human.path("confidence").asDouble(), 1e-9);
        // choice confidence falls back to the top probability
        assertEquals(0.85, out.path("results").path("department").path("confidence").asDouble(), 1e-9);
        assertEquals("DECIDED", out.path("status").asText());
    }

    @Test
    public void missingAnswerNeedsReview() throws Exception {
        String resp = "{\"answers\":{\"department\":{\"choice\":\"billing\",\"confidence\":0.99}}}";
        ObjectNode out = map(resp, 0.5, false);
        assertEquals("NEEDS_REVIEW", out.path("status").asText());
        assertTrue(out.path("results").path("needs_human").path("value").isNull());
        assertTrue(out.path("reviewReasons").get(0).asText().contains("no answer"));
    }

    @Test
    public void nullChoiceNeedsReview() throws Exception {
        String resp = "{\"answers\":{\"department\":{\"choice\":null,\"confidence\":0.99},\"needs_human\":{\"noul\":0.99}}}";
        ObjectNode out = map(resp, 0.5, false);
        assertEquals("NEEDS_REVIEW", out.path("status").asText());
        assertTrue(out.path("reviewReasons").get(0).asText().contains("no option selected"));
    }

    private static final String SCORE_QUESTION =
            "{\"frustration\":{\"type\":\"score\",\"instructions\":\"How frustrated is the customer?\","
            + "\"criteria\":[\"Calm\",\"Frustrated\",\"Very angry\"]}}";

    @Test
    public void mapsScoreWithLevelLabel() throws Exception {
        // Published example: probability-weighted score between levels, with legend and confidence
        String resp = "{\"answers\":{\"frustration\":{\"type\":\"score\",\"score\":1.05,"
                + "\"legend\":{\"0\":\"Calm\",\"1\":\"Frustrated\",\"2\":\"Very angry\"},"
                + "\"probabilities\":{\"0\":0,\"1\":0.95,\"2\":0.05},\"confidence\":0.92}}}";
        ObjectNode out = ResultMapper.map(MAPPER.readTree(resp), QuestionSet.parse(SCORE_QUESTION), 0.8, false);
        JsonNode r = out.path("results").path("frustration");
        assertEquals(1.05, r.path("value").asDouble(), 1e-9);
        assertEquals("Frustrated", r.path("level").asText());
        assertEquals(0.92, r.path("confidence").asDouble(), 1e-9);
        assertEquals(0.95, r.path("probabilities").path("1").asDouble(), 1e-9);
        assertEquals("Very angry", r.path("legend").path("2").asText());
        assertTrue(r.path("passed").asBoolean());
        assertEquals("DECIDED", out.path("status").asText());
    }

    @Test
    public void scoreConfidenceFallsBackToTopProbability() throws Exception {
        String resp = "{\"answers\":{\"frustration\":{\"score\":0.3,\"probabilities\":{\"0\":0.7,\"1\":0.3,\"2\":0}}}}";
        ObjectNode out = ResultMapper.map(MAPPER.readTree(resp), QuestionSet.parse(SCORE_QUESTION), 0.8, false);
        JsonNode r = out.path("results").path("frustration");
        assertEquals(0.7, r.path("confidence").asDouble(), 1e-9);
        assertTrue(r.path("level").isNull()); // no legend returned
        assertEquals("NEEDS_REVIEW", out.path("status").asText());
    }

    @Test
    public void missingScoreNeedsReview() throws Exception {
        ObjectNode out = ResultMapper.map(MAPPER.readTree("{\"answers\":{\"frustration\":{\"confidence\":0.9}}}"),
                QuestionSet.parse(SCORE_QUESTION), 0.8, false);
        assertEquals("NEEDS_REVIEW", out.path("status").asText());
        assertTrue(out.path("reviewReasons").get(0).asText().contains("no score"));
    }

    @Test
    public void parsesThreshold() throws Exception {
        assertNull(ResultMapper.parseThreshold(" "));
        assertEquals(0.75, ResultMapper.parseThreshold("0.75"), 1e-9);
    }

    @Test(expected = InvalidInputException.class)
    public void rejectsThresholdOutOfRange() throws Exception {
        ResultMapper.parseThreshold("1.5");
    }

    @Test(expected = InvalidInputException.class)
    public void rejectsNonNumericThreshold() throws Exception {
        ResultMapper.parseThreshold("high");
    }
}
