package com.boomi.custom.jev.review;

import com.boomi.connector.api.PayloadMetadata;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.Iterator;
import java.util.Map;
import java.util.StringJoiner;

/**
 * Copies a mapped JEV result onto the output document as Boomi properties, so later shapes can route on them
 * without a profile and Process Reporting can show them.
 *
 * <p>Dynamic document properties (when "Set Document Properties" is on):
 * <ul>
 *   <li>{@code jevStatus}, {@code jevModel}, {@code jevReviewReasons} (joined with "; "),
 *       {@code jevInputTokens}, {@code jevOutputTokens}</li>
 *   <li>per question: {@code jev_<id>} (the value), {@code jev_<id>_confidence}, {@code jev_<id>_passed},
 *       and {@code jev_<id>_level} for score questions</li>
 *   <li>{@code jevOriginalDocument} when "Keep Original Document" is also on</li>
 * </ul>
 *
 * <p>Tracked properties (when "Set Tracked Properties" is on), declared in the descriptor so they appear in
 * Process Reporting: {@code jevStatus}, {@code jevModel}, {@code jevSummary}, {@code jevReviewReasons},
 * {@code jevInputTokens}, {@code jevOutputTokens}.
 */
public final class ResultProperties {

    public static final String STATUS = "jevStatus";
    public static final String MODEL = "jevModel";
    public static final String SUMMARY = "jevSummary";
    public static final String REVIEW_REASONS = "jevReviewReasons";
    public static final String INPUT_TOKENS = "jevInputTokens";
    public static final String OUTPUT_TOKENS = "jevOutputTokens";
    public static final String ERROR_CODE = "jevErrorCode";
    public static final String ORIGINAL_DOCUMENT = "jevOriginalDocument";
    public static final String QUESTION_PREFIX = "jev_";

    /** Status value used on documents JEV rejected. */
    public static final String STATUS_ERROR = "ERROR";

    /** Keeps tracked values readable in Process Reporting. */
    static final int MAX_TRACKED_LENGTH = 1000;

    private ResultProperties() {
    }

    /** Applies the enabled property sets for a successful review. */
    public static void apply(PayloadMetadata metadata, JsonNode result, boolean documentProperties,
            boolean trackedProperties) {
        String status = result.path("status").asText("");
        String model = text(result.path("model"));
        String reasons = join(result.path("reviewReasons"));
        String inputTokens = text(result.path("usage").path("input_tokens"));
        String outputTokens = text(result.path("usage").path("output_tokens"));

        if (documentProperties) {
            setDdp(metadata, STATUS, status);
            setDdp(metadata, MODEL, model);
            setDdp(metadata, REVIEW_REASONS, reasons);
            setDdp(metadata, INPUT_TOKENS, inputTokens);
            setDdp(metadata, OUTPUT_TOKENS, outputTokens);
            Iterator<Map.Entry<String, JsonNode>> answers = result.path("results").fields();
            while (answers.hasNext()) {
                Map.Entry<String, JsonNode> answer = answers.next();
                String prefix = QUESTION_PREFIX + answer.getKey();
                JsonNode r = answer.getValue();
                setDdp(metadata, prefix, text(r.path("value")));
                setDdp(metadata, prefix + "_confidence", text(r.path("confidence")));
                setDdp(metadata, prefix + "_passed", text(r.path("passed")));
                if (r.has("level")) {
                    setDdp(metadata, prefix + "_level", text(r.path("level")));
                }
            }
        }

        if (trackedProperties) {
            setTracked(metadata, STATUS, status);
            setTracked(metadata, MODEL, model);
            setTracked(metadata, SUMMARY, summary(result.path("results")));
            setTracked(metadata, REVIEW_REASONS, reasons);
            setTracked(metadata, INPUT_TOKENS, inputTokens);
            setTracked(metadata, OUTPUT_TOKENS, outputTokens);
        }
    }

    /** Applies the enabled property sets for a document JEV rejected. */
    public static void applyError(PayloadMetadata metadata, String errorCode, boolean documentProperties,
            boolean trackedProperties) {
        if (documentProperties) {
            setDdp(metadata, STATUS, STATUS_ERROR);
            setDdp(metadata, ERROR_CODE, errorCode);
        }
        if (trackedProperties) {
            setTracked(metadata, STATUS, STATUS_ERROR);
            setTracked(metadata, SUMMARY, "JEV error " + errorCode);
        }
    }

    /** Stores the input document text, so it survives the connector replacing the document with the result. */
    public static void applyOriginalDocument(PayloadMetadata metadata, String originalText) {
        setDdp(metadata, ORIGINAL_DOCUMENT, originalText);
    }

    /** One line per document for Process Reporting, e.g. {@code department=billing; urgency=Needs attention today}. */
    static String summary(JsonNode results) {
        StringJoiner joiner = new StringJoiner("; ");
        Iterator<Map.Entry<String, JsonNode>> it = results.fields();
        while (it.hasNext()) {
            Map.Entry<String, JsonNode> e = it.next();
            JsonNode r = e.getValue();
            String value = r.hasNonNull("level") ? text(r.path("level")) : text(r.path("value"));
            joiner.add(e.getKey() + "=" + (value == null ? "" : value));
        }
        return joiner.toString();
    }

    private static String join(JsonNode array) {
        if (!array.isArray() || array.size() == 0) {
            return "";
        }
        StringJoiner joiner = new StringJoiner("; ");
        array.forEach(n -> joiner.add(n.asText()));
        return joiner.toString();
    }

    /** Scalar as text; null for missing or JSON null so the property is simply not set. */
    private static String text(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        return node.isValueNode() ? node.asText() : node.toString();
    }

    private static void setDdp(PayloadMetadata metadata, String name, String value) {
        if (value != null) {
            metadata.setUserDefinedProperty(name, value);
        }
    }

    private static void setTracked(PayloadMetadata metadata, String name, String value) {
        if (value != null) {
            metadata.setTrackedProperty(name,
                    value.length() > MAX_TRACKED_LENGTH ? value.substring(0, MAX_TRACKED_LENGTH) : value);
        }
    }
}
