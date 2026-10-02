package com.boomi.custom.jev;

/**
 * Field IDs shared by {@code connector-descriptor.xml} and the Java code. Keep the two in sync.
 */
public final class JevConstants {

    // Connection fields
    public static final String BASE_URL = "baseUrl";
    public static final String ENDPOINT_PATH = "endpointPath";
    public static final String API_KEY = "apiKey";
    public static final String AUTH_HEADER_NAME = "authHeaderName";
    public static final String AUTH_SCHEME = "authScheme";
    public static final String DEFAULT_MODEL = "defaultModel";
    public static final String CONNECT_TIMEOUT_MS = "connectTimeoutMs";
    public static final String READ_TIMEOUT_MS = "readTimeoutMs";
    public static final String MAX_RETRIES = "maxRetries";

    // Operation fields
    public static final String REQUEST_MODE = "requestMode";
    public static final String QUESTION_SET = "questionSet";
    public static final String MODEL = "model";
    public static final String STATE_FORMAT = "stateFormat";
    public static final String STATE_KEY = "stateKey";
    public static final String CONFIDENCE_THRESHOLD = "confidenceThreshold";
    public static final String INCLUDE_RAW_RESPONSE = "includeRawResponse";
    public static final String MAX_DOCUMENT_SIZE_KB = "maxDocumentSizeKb";
    public static final String SET_DOCUMENT_PROPERTIES = "setDocumentProperties";
    public static final String KEEP_ORIGINAL_DOCUMENT = "keepOriginalDocument";
    public static final String SET_TRACKED_PROPERTIES = "setTrackedProperties";

    // Connector document properties (Set Properties > Connectors > JEV). Deliberately distinct from the operation
    // field IDs so an unset document property can never shadow the operation's value.
    public static final String DOC_PROP_QUESTION_SET = "jevQuestionSet";
    public static final String DOC_PROP_MODEL = "jevModel";
    public static final String DOC_PROP_CONFIDENCE_THRESHOLD = "jevConfidenceThreshold";

    // Defaults (mirror the descriptor so missing values behave the same in tests and at runtime)
    public static final String DEFAULT_BASE_URL = "https://api.typesafe.ai";
    public static final String DEFAULT_ENDPOINT_PATH = "/v1/systemone";
    public static final String DEFAULT_AUTH_HEADER_NAME = "Authorization";
    public static final String DEFAULT_AUTH_SCHEME = "Bearer";
    public static final String DEFAULT_MODEL_NAME = "jev-latest";
    public static final long DEFAULT_CONNECT_TIMEOUT_MS = 10_000L;
    public static final long DEFAULT_READ_TIMEOUT_MS = 60_000L;
    public static final long DEFAULT_MAX_RETRIES = 2L;
    public static final long DEFAULT_MAX_DOCUMENT_SIZE_KB = 1024L;

    /** The single browsable object type. */
    public static final String OBJECT_TYPE_REVIEW = "DocumentReview";

    private JevConstants() {
    }
}
