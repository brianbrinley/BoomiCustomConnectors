package com.boomi.custom.jev.review;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * A validated JEV {@code questions} object, keyed by question ID.
 *
 * <pre>
 * {
 *   "department":  {"type": "choice", "instructions": "...", "criteria": {"billing": "...", "technical": "..."}},
 *   "needs_human": {"type": "noul",   "instructions": "..."},
 *   "urgency":     {"type": "score",  "instructions": "...", "criteria": ["Low", "Medium", "High"]}
 * }
 * </pre>
 */
public final class QuestionSet {

    public static final String TYPE_CHOICE = "choice";
    public static final String TYPE_SCORE = "score";
    public static final String TYPE_NOUL = "noul";

    /** JEV accepts 2 to 10 ordered levels for a score question. */
    static final int MIN_SCORE_LEVELS = 2;
    static final int MAX_SCORE_LEVELS = 10;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ObjectNode questions;
    private final Map<String, String> types;

    private QuestionSet(ObjectNode questions, Map<String, String> types) {
        this.questions = questions;
        this.types = Collections.unmodifiableMap(types);
    }

    public static QuestionSet parse(String json) throws InvalidInputException {
        if (json == null || json.trim().isEmpty()) {
            throw new InvalidInputException("Question Set is required in Document Review mode. Set it on the operation, "
                    + "on the connector shape's Dynamic Operation Properties tab, as a JEV connector document property "
                    + "(Set Properties > Connectors > JEV > Question Set), or as a dynamic document property named 'questionSet'");
        }
        JsonNode node;
        try {
            node = MAPPER.readTree(json);
        } catch (JsonProcessingException e) {
            throw new InvalidInputException("Question Set is not valid JSON: " + e.getOriginalMessage(), e);
        }
        return fromNode(node);
    }

    public static QuestionSet fromNode(JsonNode node) throws InvalidInputException {
        if (node == null || !node.isObject() || node.size() == 0) {
            throw new InvalidInputException("Question Set must be a non-empty JSON object keyed by question ID");
        }
        ObjectNode copy = ((ObjectNode) node).deepCopy();
        Map<String, String> types = new LinkedHashMap<>();
        Iterator<Map.Entry<String, JsonNode>> it = copy.fields();
        while (it.hasNext()) {
            Map.Entry<String, JsonNode> entry = it.next();
            String id = entry.getKey();
            JsonNode q = entry.getValue();
            if (!q.isObject()) {
                throw new InvalidInputException("Question '" + id + "' must be a JSON object");
            }
            String type = q.path("type").asText("").trim().toLowerCase(Locale.ROOT);
            if (!TYPE_CHOICE.equals(type) && !TYPE_SCORE.equals(type) && !TYPE_NOUL.equals(type)) {
                throw new InvalidInputException(
                        "Question '" + id + "' has invalid type '" + q.path("type").asText("") + "' (expected choice, score or noul)");
            }
            if (q.path("instructions").asText("").trim().isEmpty()) {
                throw new InvalidInputException("Question '" + id + "' is missing 'instructions'");
            }
            if (TYPE_CHOICE.equals(type)) {
                JsonNode criteria = q.path("criteria");
                if (!criteria.isObject() || criteria.size() == 0) {
                    throw new InvalidInputException(
                            "Choice question '" + id + "' needs a non-empty 'criteria' object of option ID to description");
                }
            } else if (TYPE_SCORE.equals(type)) {
                validateScoreCriteria(id, q.path("criteria"));
            }
            ((ObjectNode) q).put("type", type);
            types.put(id, type);
        }
        return new QuestionSet(copy, types);
    }

    private static void validateScoreCriteria(String id, JsonNode criteria) throws InvalidInputException {
        if (!criteria.isArray() || criteria.size() < MIN_SCORE_LEVELS || criteria.size() > MAX_SCORE_LEVELS) {
            throw new InvalidInputException("Score question '" + id + "' needs 'criteria' as an array of "
                    + MIN_SCORE_LEVELS + " to " + MAX_SCORE_LEVELS + " level descriptions, lowest first");
        }
        for (JsonNode level : criteria) {
            if (!level.isTextual() || level.asText().trim().isEmpty()) {
                throw new InvalidInputException(
                        "Score question '" + id + "' has a blank or non-text level in 'criteria'");
            }
        }
    }

    /** Deep copy of the questions, safe to embed in a request. */
    public ObjectNode toJson() {
        return questions.deepCopy();
    }

    /** Question ID to normalized type, in declaration order. */
    public Map<String, String> getTypes() {
        return types;
    }

    /** Option IDs of a choice question, in declaration order (empty for other types). */
    public Iterable<String> choiceOptions(String questionId) {
        JsonNode criteria = questions.path(questionId).path("criteria");
        return criteria.isObject() ? criteria::fieldNames : Collections::emptyIterator;
    }

    /** Number of levels of a score question (0 for other types). */
    public int scoreLevels(String questionId) {
        JsonNode criteria = questions.path(questionId).path("criteria");
        return criteria.isArray() ? criteria.size() : 0;
    }
}
