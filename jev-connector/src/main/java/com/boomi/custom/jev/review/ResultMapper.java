package com.boomi.custom.jev.review;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Iterator;
import java.util.Map;

/**
 * Turns a JEV response into the connector's structured output document:
 *
 * <pre>
 * {
 *   "status": "DECIDED" | "NEEDS_REVIEW",
 *   "model": "jev-1.13.0",
 *   "confidenceThreshold": 0.8,
 *   "results": {
 *     "department":  {"type": "choice", "value": "billing", "confidence": 0.92, "passed": true, "probabilities": {...}},
 *     "needs_human": {"type": "noul",   "value": true, "probability": 0.87, "confidence": 0.87, "passed": true},
 *     "urgency":     {"type": "score",  "value": 0.7, "passed": true}
 *   },
 *   "reviewReasons": [],
 *   "usage": {...},
 *   "raw": {...}            // only when "Include Raw JEV Response" is on
 * }
 * </pre>
 *
 * Confidence per type: choice uses JEV's {@code confidence}, else the top probability; noul uses JEV's
 * {@code confidence}, else {@code max(p, 1 - p)}; score uses JEV's {@code confidence} when present. An answer with no
 * confidence cannot be gated and is treated as passed.
 */
public final class ResultMapper {

    public static final String STATUS_DECIDED = "DECIDED";
    public static final String STATUS_NEEDS_REVIEW = "NEEDS_REVIEW";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ResultMapper() {
    }

    public static ObjectNode map(JsonNode response, QuestionSet questions, Double threshold, boolean includeRaw) {
        ObjectNode out = MAPPER.createObjectNode();
        ObjectNode results = MAPPER.createObjectNode();
        ArrayNode reasons = MAPPER.createArrayNode();
        JsonNode answers = response.path("answers");

        for (Map.Entry<String, String> question : questions.getTypes().entrySet()) {
            String id = question.getKey();
            JsonNode answer = answers.path(id);
            if (!answer.isObject()) {
                ObjectNode missing = MAPPER.createObjectNode();
                missing.put("type", question.getValue());
                missing.putNull("value");
                missing.put("passed", false);
                results.set(id, missing);
                reasons.add(id + ": no answer returned");
                continue;
            }
            results.set(id, mapAnswer(id, question.getValue(), answer, threshold, reasons));
        }

        // Answers JEV returned for questions we did not ask (raw mode with extra keys) are passed through untouched
        Iterator<Map.Entry<String, JsonNode>> extra = answers.fields();
        while (extra.hasNext()) {
            Map.Entry<String, JsonNode> e = extra.next();
            if (!results.has(e.getKey())) {
                results.set(e.getKey(), e.getValue().deepCopy());
            }
        }

        out.put("status", reasons.size() == 0 ? STATUS_DECIDED : STATUS_NEEDS_REVIEW);
        if (response.path("model").isTextual()) {
            out.put("model", response.path("model").asText());
        } else {
            out.putNull("model");
        }
        if (threshold == null) {
            out.putNull("confidenceThreshold");
        } else {
            out.put("confidenceThreshold", threshold);
        }
        out.set("results", results);
        out.set("reviewReasons", reasons);
        if (response.has("usage")) {
            out.set("usage", response.get("usage").deepCopy());
        }
        if (includeRaw) {
            out.set("raw", response.deepCopy());
        }
        return out;
    }

    private static ObjectNode mapAnswer(String id, String type, JsonNode answer, Double threshold, ArrayNode reasons) {
        ObjectNode result = MAPPER.createObjectNode();
        result.put("type", type);
        Double confidence = number(answer.get("confidence"));

        switch (type) {
            case QuestionSet.TYPE_CHOICE: {
                JsonNode choice = answer.path("choice");
                if (choice.isTextual()) {
                    result.put("value", choice.asText());
                } else {
                    result.putNull("value");
                }
                JsonNode probabilities = answer.path("probabilities");
                if (confidence == null && probabilities.isObject()) {
                    confidence = maxProbability(probabilities);
                }
                if (probabilities.isObject()) {
                    result.set("probabilities", probabilities.deepCopy());
                }
                if (!choice.isTextual()) {
                    reasons.add(id + ": no option selected");
                    putConfidence(result, confidence);
                    result.put("passed", false);
                    return result;
                }
                break;
            }
            case QuestionSet.TYPE_NOUL: {
                Double p = number(answer.get("noul"));
                if (p == null) {
                    result.putNull("value");
                    result.putNull("probability");
                    putConfidence(result, confidence);
                    result.put("passed", false);
                    reasons.add(id + ": no probability returned");
                    return result;
                }
                result.put("value", p >= 0.5);
                result.put("probability", p);
                if (confidence == null) {
                    confidence = Math.max(p, 1.0 - p);
                }
                break;
            }
            case QuestionSet.TYPE_SCORE:
            default: {
                JsonNode score = answer.has(type) ? answer.get(type) : answer.path("value");
                if (score.isMissingNode()) {
                    result.putNull("value");
                } else {
                    result.set("value", score.deepCopy());
                }
                break;
            }
        }

        putConfidence(result, confidence);
        boolean passed = threshold == null || confidence == null || confidence >= threshold;
        result.put("passed", passed);
        if (!passed) {
            reasons.add(String.format("%s: confidence %.4f below threshold %.4f", id, confidence, threshold));
        }
        return result;
    }

    private static void putConfidence(ObjectNode result, Double confidence) {
        if (confidence == null) {
            result.putNull("confidence");
        } else {
            result.put("confidence", confidence);
        }
    }

    private static Double maxProbability(JsonNode probabilities) {
        Double max = null;
        for (JsonNode p : probabilities) {
            if (p.isNumber() && (max == null || p.asDouble() > max)) {
                max = p.asDouble();
            }
        }
        return max;
    }

    private static Double number(JsonNode node) {
        return node != null && node.isNumber() ? node.asDouble() : null;
    }

    /** Parses the Confidence Threshold field: blank means no gating, otherwise a number in [0, 1]. */
    public static Double parseThreshold(String value) throws InvalidInputException {
        if (value == null || value.trim().isEmpty()) {
            return null;
        }
        try {
            double d = Double.parseDouble(value.trim());
            if (d < 0.0 || d > 1.0 || Double.isNaN(d)) {
                throw new InvalidInputException("Confidence Threshold must be between 0 and 1, got " + value);
            }
            return d;
        } catch (NumberFormatException e) {
            throw new InvalidInputException("Confidence Threshold must be a number between 0 and 1, got " + value, e);
        }
    }
}
