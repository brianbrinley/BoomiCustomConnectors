package com.boomi.custom.jev.client;

import com.boomi.connector.api.ConnectorException;
import com.boomi.connector.api.PropertyMap;
import com.boomi.custom.jev.JevConstants;

import java.net.MalformedURLException;
import java.net.URL;

/**
 * Immutable view of the connection fields.
 */
public final class JevSettings {

    private final URL endpoint;
    private final String apiKey;
    private final String authHeaderName;
    private final String authScheme;
    private final String defaultModel;
    private final int connectTimeoutMs;
    private final int readTimeoutMs;
    private final int maxRetries;

    public JevSettings(String baseUrl, String endpointPath, String apiKey, String authHeaderName,
            String authScheme, String defaultModel, long connectTimeoutMs, long readTimeoutMs, long maxRetries) {
        if (isBlank(baseUrl)) {
            throw new ConnectorException("JEV Base URL is required");
        }
        if (isBlank(apiKey)) {
            throw new ConnectorException("JEV API Key is required");
        }
        this.endpoint = buildEndpoint(baseUrl.trim(), endpointPath);
        this.apiKey = apiKey.trim();
        this.authHeaderName = isBlank(authHeaderName) ? JevConstants.DEFAULT_AUTH_HEADER_NAME : authHeaderName.trim();
        this.authScheme = authScheme == null ? "" : authScheme.trim();
        this.defaultModel = isBlank(defaultModel) ? JevConstants.DEFAULT_MODEL_NAME : defaultModel.trim();
        this.connectTimeoutMs = (int) Math.max(0, connectTimeoutMs);
        this.readTimeoutMs = (int) Math.max(0, readTimeoutMs);
        this.maxRetries = (int) Math.max(0, maxRetries);
    }

    public static JevSettings from(PropertyMap props) {
        return new JevSettings(
                props.getProperty(JevConstants.BASE_URL, JevConstants.DEFAULT_BASE_URL),
                props.getProperty(JevConstants.ENDPOINT_PATH, JevConstants.DEFAULT_ENDPOINT_PATH),
                props.getProperty(JevConstants.API_KEY),
                props.getProperty(JevConstants.AUTH_HEADER_NAME, JevConstants.DEFAULT_AUTH_HEADER_NAME),
                props.getProperty(JevConstants.AUTH_SCHEME, JevConstants.DEFAULT_AUTH_SCHEME),
                props.getProperty(JevConstants.DEFAULT_MODEL, JevConstants.DEFAULT_MODEL_NAME),
                props.getLongProperty(JevConstants.CONNECT_TIMEOUT_MS, JevConstants.DEFAULT_CONNECT_TIMEOUT_MS),
                props.getLongProperty(JevConstants.READ_TIMEOUT_MS, JevConstants.DEFAULT_READ_TIMEOUT_MS),
                props.getLongProperty(JevConstants.MAX_RETRIES, JevConstants.DEFAULT_MAX_RETRIES));
    }

    private static URL buildEndpoint(String baseUrl, String endpointPath) {
        String base = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        String path = isBlank(endpointPath) ? JevConstants.DEFAULT_ENDPOINT_PATH : endpointPath.trim();
        if (!path.startsWith("/")) {
            path = "/" + path;
        }
        try {
            return new URL(base + path);
        } catch (MalformedURLException e) {
            throw new ConnectorException("Invalid JEV Base URL or endpoint path: " + base + path, e);
        }
    }

    static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }

    public URL getEndpoint() {
        return endpoint;
    }

    public String getAuthHeaderName() {
        return authHeaderName;
    }

    /** Full auth header value, e.g. {@code Bearer <key>}. Never log this. */
    public String getAuthHeaderValue() {
        return authScheme.isEmpty() ? apiKey : authScheme + " " + apiKey;
    }

    public String getDefaultModel() {
        return defaultModel;
    }

    public int getConnectTimeoutMs() {
        return connectTimeoutMs;
    }

    public int getReadTimeoutMs() {
        return readTimeoutMs;
    }

    public int getMaxRetries() {
        return maxRetries;
    }
}
