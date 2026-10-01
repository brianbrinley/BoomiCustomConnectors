package com.boomi.custom.jev.review;

/** How a document's text is represented in the JEV {@code state} field. */
public enum StateFormat {
    /** Valid JSON objects/arrays are sent as JSON; everything else as a string. */
    AUTO,
    /** Document must be valid JSON. */
    JSON,
    /** Document is always sent as a string. */
    TEXT;

    public static StateFormat fromValue(String value) {
        if (value == null || value.trim().isEmpty()) {
            return AUTO;
        }
        return valueOf(value.trim().toUpperCase());
    }
}
