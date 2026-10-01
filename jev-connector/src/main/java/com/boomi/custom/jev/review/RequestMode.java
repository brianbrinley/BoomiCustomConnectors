package com.boomi.custom.jev.review;

/** How the input document is turned into a JEV request. */
public enum RequestMode {
    /** The document becomes {@code state}; questions come from the operation's Question Set. */
    DOCUMENT_REVIEW,
    /** The document is already a complete JEV request body. */
    RAW_REQUEST;

    public static RequestMode fromValue(String value) {
        if (value == null || value.trim().isEmpty()) {
            return DOCUMENT_REVIEW;
        }
        return valueOf(value.trim().toUpperCase());
    }
}
