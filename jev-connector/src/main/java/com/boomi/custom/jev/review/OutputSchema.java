package com.boomi.custom.jev.review;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Map;

/**
 * JSON Schema (draft-04) for the output document produced by {@link ResultMapper}. When a Question Set is known at
 * import time, each question ID becomes a typed property under {@code results} so it can be mapped directly in Boomi.
 */
public final class OutputSchema {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private OutputSchema() {
    }

    public static String build(QuestionSet questions) {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("$schema", "http://json-schema.org/draft-04/schema#");
        root.put("title", "JEV Review Result");
        root.put("type", "object");
        ObjectNode props = root.putObject("properties");

        ObjectNode status = typed(props.putObject("status"), "string");
        ArrayNode statusEnum = status.putArray("enum");
        statusEnum.add(ResultMapper.STATUS_DECIDED).add(ResultMapper.STATUS_NEEDS_REVIEW);
        typed(props.putObject("model"), "string");
        typed(props.putObject("confidenceThreshold"), "number");

        ObjectNode results = typed(props.putObject("results"), "object");
        if (questions != null) {
            ObjectNode resultProps = results.putObject("properties");
            for (Map.Entry<String, String> q : questions.getTypes().entrySet()) {
                resultProps.set(q.getKey(), answerSchema(q.getKey(), q.getValue(), questions));
            }
        }

        ObjectNode reasons = typed(props.putObject("reviewReasons"), "array");
        typed(reasons.putObject("items"), "string");

        ObjectNode usage = typed(props.putObject("usage"), "object");
        ObjectNode usageProps = usage.putObject("properties");
        typed(usageProps.putObject("input_tokens"), "integer");
        typed(usageProps.putObject("output_tokens"), "integer");

        typed(props.putObject("raw"), "object");
        return root.toPrettyString();
    }

    private static ObjectNode answerSchema(String id, String type, QuestionSet questions) {
        ObjectNode schema = MAPPER.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");
        typed(props.putObject("type"), "string");
        switch (type) {
            case QuestionSet.TYPE_CHOICE: {
                typed(props.putObject("value"), "string");
                ObjectNode probabilities = typed(props.putObject("probabilities"), "object");
                ObjectNode probProps = probabilities.putObject("properties");
                for (String option : questions.choiceOptions(id)) {
                    typed(probProps.putObject(option), "number");
                }
                break;
            }
            case QuestionSet.TYPE_NOUL:
                typed(props.putObject("value"), "boolean");
                typed(props.putObject("probability"), "number");
                break;
            case QuestionSet.TYPE_SCORE: {
                typed(props.putObject("value"), "number");
                typed(props.putObject("level"), "string");
                ObjectNode probProps = typed(props.putObject("probabilities"), "object").putObject("properties");
                ObjectNode legendProps = typed(props.putObject("legend"), "object").putObject("properties");
                for (int i = 0; i < questions.scoreLevels(id); i++) {
                    typed(probProps.putObject(String.valueOf(i)), "number");
                    typed(legendProps.putObject(String.valueOf(i)), "string");
                }
                break;
            }
            default:
                typed(props.putObject("value"), "number");
                break;
        }
        typed(props.putObject("confidence"), "number");
        typed(props.putObject("passed"), "boolean");
        return schema;
    }

    private static ObjectNode typed(ObjectNode node, String type) {
        node.put("type", type);
        return node;
    }
}
