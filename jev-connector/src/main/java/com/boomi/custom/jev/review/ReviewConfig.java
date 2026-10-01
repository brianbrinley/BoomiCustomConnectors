package com.boomi.custom.jev.review;

import com.boomi.connector.api.DynamicPropertyMap;
import com.boomi.connector.api.PropertyMap;
import com.boomi.connector.api.TrackedData;
import com.boomi.custom.jev.JevConstants;

import java.util.Map;
import java.util.TreeSet;

/**
 * Operation settings for one document: the operation's fields, with any per-document overrides applied
 * (see {@link #withOverrides(TrackedData)}).
 */
public final class ReviewConfig {

    private final RequestMode mode;
    private final String questionSetJson;
    private final String model;
    private final StateFormat stateFormat;
    private final String stateKey;
    private final String thresholdValue;
    private final boolean includeRaw;
    private final long maxDocumentBytes;
    private final String questionSetSources;

    private ReviewConfig(RequestMode mode, String questionSetJson, String model, StateFormat stateFormat,
            String stateKey, String thresholdValue, boolean includeRaw, long maxDocumentBytes) {
        this(mode, questionSetJson, model, stateFormat, stateKey, thresholdValue, includeRaw, maxDocumentBytes,
                "operation field: " + describe(questionSetJson));
    }

    private ReviewConfig(RequestMode mode, String questionSetJson, String model, StateFormat stateFormat,
            String stateKey, String thresholdValue, boolean includeRaw, long maxDocumentBytes,
            String questionSetSources) {
        this.questionSetSources = questionSetSources;
        this.mode = mode;
        this.questionSetJson = questionSetJson;
        this.model = model;
        this.stateFormat = stateFormat;
        this.stateKey = stateKey;
        this.thresholdValue = thresholdValue;
        this.includeRaw = includeRaw;
        this.maxDocumentBytes = maxDocumentBytes;
    }

    public static ReviewConfig from(PropertyMap op, String defaultModel) {
        String model = op.getProperty(JevConstants.MODEL);
        Long maxKb = op.getLongProperty(JevConstants.MAX_DOCUMENT_SIZE_KB, JevConstants.DEFAULT_MAX_DOCUMENT_SIZE_KB);
        return new ReviewConfig(
                RequestMode.fromValue(op.getProperty(JevConstants.REQUEST_MODE)),
                op.getProperty(JevConstants.QUESTION_SET),
                isBlank(model) ? defaultModel : model.trim(),
                StateFormat.fromValue(op.getProperty(JevConstants.STATE_FORMAT)),
                op.getProperty(JevConstants.STATE_KEY),
                op.getProperty(JevConstants.CONFIDENCE_THRESHOLD),
                Boolean.TRUE.equals(op.getBooleanProperty(JevConstants.INCLUDE_RAW_RESPONSE, Boolean.FALSE)),
                maxKb == null ? 0L : maxKb * 1024L);
    }

    /**
     * Applies per-document values of the overrideable fields. Boomi can deliver them three ways; the first
     * non-blank value wins, falling back to the operation field:
     * <ol>
     *   <li>JEV connector document property (Set Properties &gt; Connectors &gt; JEV), e.g. {@code jevQuestionSet}</li>
     *   <li>Dynamic document property whose name matches the field ID, ignoring case
     *       (e.g. {@code questionSet}, {@code model}, {@code confidenceThreshold})</li>
     *   <li>Dynamic Operation Properties tab on the connector shape</li>
     * </ol>
     */
    public ReviewConfig withOverrides(TrackedData document) {
        if (document == null) {
            return this;
        }
        String questions = override(document, JevConstants.QUESTION_SET, JevConstants.DOC_PROP_QUESTION_SET);
        String overrideModel = override(document, JevConstants.MODEL, JevConstants.DOC_PROP_MODEL);
        String threshold = override(document, JevConstants.CONFIDENCE_THRESHOLD,
                JevConstants.DOC_PROP_CONFIDENCE_THRESHOLD);
        return new ReviewConfig(mode,
                isBlank(questions) ? questionSetJson : questions,
                isBlank(overrideModel) ? model : overrideModel.trim(),
                stateFormat, stateKey,
                isBlank(threshold) ? thresholdValue : threshold,
                includeRaw, maxDocumentBytes,
                describeQuestionSetSources(document));
    }

    private static String override(TrackedData document, String fieldId, String documentPropertyId) {
        String value = document.getDynamicProperties().get(documentPropertyId);
        if (isBlank(value)) {
            value = getIgnoreCase(document.getUserDefinedProperties(), fieldId);
        }
        if (isBlank(value)) {
            DynamicPropertyMap operationProps = document.getDynamicOperationProperties();
            value = operationProps == null ? null : operationProps.getProperty(fieldId);
        }
        return value;
    }

    /** What each source held for the Question Set; included in the "required" error to make setup issues visible. */
    private String describeQuestionSetSources(TrackedData document) {
        DynamicPropertyMap operationProps = document.getDynamicOperationProperties();
        Map<String, String> ddps = document.getUserDefinedProperties();
        return questionSetSources
                + "; Dynamic Operation Properties: "
                + describe(operationProps == null ? null : operationProps.getProperty(JevConstants.QUESTION_SET))
                + "; connector document property " + JevConstants.DOC_PROP_QUESTION_SET + ": "
                + describe(document.getDynamicProperties().get(JevConstants.DOC_PROP_QUESTION_SET))
                + "; connector document properties present: " + names(document.getDynamicProperties())
                + "; dynamic document properties present: " + names(ddps);
    }

    private static String names(Map<String, String> props) {
        return props == null || props.isEmpty() ? "none" : new TreeSet<>(props.keySet()).toString();
    }

    private static String describe(String value) {
        if (value == null) {
            return "not set";
        }
        return value.trim().isEmpty() ? "empty" : value.length() + " chars";
    }

    /** Where the connector looked for the Question Set and what it found (values are not included). */
    public String getQuestionSetSources() {
        return questionSetSources;
    }

    private static String getIgnoreCase(Map<String, String> props, String key) {
        if (props == null) {
            return null;
        }
        String exact = props.get(key);
        if (exact != null) {
            return exact;
        }
        for (Map.Entry<String, String> e : props.entrySet()) {
            if (key.equalsIgnoreCase(e.getKey())) {
                return e.getValue();
            }
        }
        return null;
    }

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }

    public RequestMode getMode() {
        return mode;
    }

    public String getQuestionSetJson() {
        return questionSetJson;
    }

    public String getModel() {
        return model;
    }

    public StateFormat getStateFormat() {
        return stateFormat;
    }

    public String getStateKey() {
        return stateKey;
    }

    public Double getThreshold() throws InvalidInputException {
        return ResultMapper.parseThreshold(thresholdValue);
    }

    public boolean isIncludeRaw() {
        return includeRaw;
    }

    public long getMaxDocumentBytes() {
        return maxDocumentBytes;
    }
}
