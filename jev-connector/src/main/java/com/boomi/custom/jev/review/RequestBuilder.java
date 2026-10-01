package com.boomi.custom.jev.review;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;

/**
 * Builds JEV request bodies: {@code {"model": ..., "state": ..., "questions": {...}}}.
 */
public final class RequestBuilder {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private RequestBuilder() {
    }

    /** Document Review mode: the document text becomes {@code state}. */
    public static ObjectNode forReview(String documentText, StateFormat format, String stateKey,
            QuestionSet questions, String model) throws InvalidInputException {
        if (documentText.trim().isEmpty()) {
            throw new InvalidInputException("Document is empty");
        }
        JsonNode state = toState(documentText, format);
        if (stateKey != null && !stateKey.trim().isEmpty()) {
            ObjectNode wrapper = MAPPER.createObjectNode();
            wrapper.set(stateKey.trim(), state);
            state = wrapper;
        }
        ObjectNode request = MAPPER.createObjectNode();
        request.put("model", model);
        request.set("state", state);
        request.set("questions", questions.toJson());
        return request;
    }

    /**
     * Raw Request mode: the document is a full request. Fills in {@code model} when absent and validates the rest.
     * Returns the request together with its parsed question set so results can be typed.
     */
    public static RawRequest fromRaw(String documentText, String model) throws InvalidInputException {
        JsonNode node;
        try {
            node = MAPPER.readTree(documentText);
        } catch (JsonProcessingException e) {
            throw new InvalidInputException("Raw Request document is not valid JSON: " + e.getOriginalMessage(), e);
        }
        if (node == null || !node.isObject()) {
            throw new InvalidInputException("Raw Request document must be a JSON object");
        }
        ObjectNode request = (ObjectNode) node;
        if (!request.hasNonNull("state")) {
            throw new InvalidInputException("Raw Request document is missing 'state'");
        }
        QuestionSet questions = QuestionSet.fromNode(request.get("questions"));
        if (!request.path("model").isTextual() || request.path("model").asText().trim().isEmpty()) {
            request.put("model", model);
        }
        request.set("questions", questions.toJson());
        return new RawRequest(request, questions);
    }

    static JsonNode toState(String text, StateFormat format) throws InvalidInputException {
        switch (format) {
            case TEXT:
                return TextNode.valueOf(text);
            case JSON:
                try {
                    return MAPPER.readTree(text);
                } catch (JsonProcessingException e) {
                    throw new InvalidInputException(
                            "Document Format is JSON but the document is not valid JSON: " + e.getOriginalMessage(), e);
                }
            case AUTO:
            default:
                String trimmed = text.trim();
                if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
                    try {
                        return MAPPER.readTree(trimmed);
                    } catch (JsonProcessingException e) {
                        // Looks like JSON but isn't; send it as plain text
                    }
                }
                return TextNode.valueOf(text);
        }
    }

    /** A validated raw request and its questions. */
    public static final class RawRequest {
        private final ObjectNode request;
        private final QuestionSet questions;

        RawRequest(ObjectNode request, QuestionSet questions) {
            this.request = request;
            this.questions = questions;
        }

        public ObjectNode getRequest() {
            return request;
        }

        public QuestionSet getQuestions() {
            return questions;
        }
    }
}
