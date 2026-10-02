package com.boomi.custom.jev.review;

import com.boomi.custom.jev.JevTestData;
import org.junit.Test;

import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class QuestionSetTest {

    @Test
    public void parsesAndNormalizesTypes() throws Exception {
        QuestionSet qs = QuestionSet.parse(
                "{\"q1\":{\"type\":\"NOUL\",\"instructions\":\"Is it urgent?\"},\"q2\":{\"type\":\"score\",\"instructions\":\"How urgent?\",\"criteria\":[\"Low\",\"High\"]}}");
        assertEquals(Arrays.asList("q1", "q2"), Arrays.asList(qs.getTypes().keySet().toArray()));
        assertEquals("noul", qs.getTypes().get("q1"));
        assertEquals("noul", qs.toJson().path("q1").path("type").asText());
    }

    @Test
    public void exposesChoiceOptions() throws Exception {
        QuestionSet qs = QuestionSet.parse(JevTestData.QUESTION_SET);
        StringBuilder options = new StringBuilder();
        qs.choiceOptions("department").forEach(o -> options.append(o).append(','));
        assertEquals("billing,technical,sales,", options.toString());
    }

    @Test
    public void rejectsInvalidDefinitions() {
        assertRejected("", "required");
        assertRejected("not json", "not valid JSON");
        assertRejected("{}", "non-empty");
        assertRejected("[]", "non-empty");
        assertRejected("{\"q\":{\"type\":\"essay\",\"instructions\":\"x\"}}", "invalid type");
        assertRejected("{\"q\":{\"type\":\"noul\"}}", "instructions");
        assertRejected("{\"q\":{\"type\":\"choice\",\"instructions\":\"x\"}}", "criteria");
    }

    @Test
    public void scoreNeedsTwoToTenTextLevels() throws Exception {
        assertRejected("{\"q\":{\"type\":\"score\",\"instructions\":\"x\"}}", "2 to 10 level");
        assertRejected("{\"q\":{\"type\":\"score\",\"instructions\":\"x\",\"criteria\":{\"a\":\"b\"}}}", "array");
        assertRejected("{\"q\":{\"type\":\"score\",\"instructions\":\"x\",\"criteria\":[\"only one\"]}}", "2 to 10");
        assertRejected("{\"q\":{\"type\":\"score\",\"instructions\":\"x\",\"criteria\":"
                + "[\"1\",\"2\",\"3\",\"4\",\"5\",\"6\",\"7\",\"8\",\"9\",\"10\",\"11\"]}}", "2 to 10");
        assertRejected("{\"q\":{\"type\":\"score\",\"instructions\":\"x\",\"criteria\":[\"Low\",\" \"]}}", "blank");

        QuestionSet qs = QuestionSet.parse(
                "{\"q\":{\"type\":\"score\",\"instructions\":\"x\",\"criteria\":[\"Low\",\"Medium\",\"High\"]}}");
        assertEquals(3, qs.scoreLevels("q"));
    }

    private static void assertRejected(String json, String messagePart) {
        try {
            QuestionSet.parse(json);
            fail("expected rejection of " + json);
        } catch (InvalidInputException e) {
            assertTrue(e.getMessage(), e.getMessage().contains(messagePart));
        }
    }
}
