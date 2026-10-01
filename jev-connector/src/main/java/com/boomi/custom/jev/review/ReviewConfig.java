package com.boomi.custom.jev.review;

import com.boomi.connector.api.DynamicPropertyMap;
import com.boomi.connector.api.PropertyMap;
import com.boomi.custom.jev.JevConstants;

/**
 * Operation settings for one document: the operation's fields, with any per-document overrides applied
 * (fields marked {@code overrideable} in the descriptor arrive as dynamic operation properties).
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

    private ReviewConfig(RequestMode mode, String questionSetJson, String model, StateFormat stateFormat,
            String stateKey, String thresholdValue, boolean includeRaw, long maxDocumentBytes) {
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

    /** Applies per-document overrides of the overrideable fields. */
    public ReviewConfig withOverrides(DynamicPropertyMap overrides) {
        if (overrides == null) {
            return this;
        }
        String questions = overrides.getProperty(JevConstants.QUESTION_SET);
        String overrideModel = overrides.getProperty(JevConstants.MODEL);
        String threshold = overrides.getProperty(JevConstants.CONFIDENCE_THRESHOLD);
        if (isBlank(questions) && isBlank(overrideModel) && isBlank(threshold)) {
            return this;
        }
        return new ReviewConfig(mode,
                isBlank(questions) ? questionSetJson : questions,
                isBlank(overrideModel) ? model : overrideModel.trim(),
                stateFormat, stateKey,
                isBlank(threshold) ? thresholdValue : threshold,
                includeRaw, maxDocumentBytes);
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
