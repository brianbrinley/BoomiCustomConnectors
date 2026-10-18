package com.boomi.connector.muse;

import java.net.URI;

import com.boomi.connector.api.PropertyMap;

/**
 * Resolved connection configuration. Every field carries its own fallback, so
 * the connector never depends on a GUI default reaching the runtime — the
 * descriptor's defaults are a design-time convenience only.
 */
public final class MuseConnection {

    private final String _baseUrl;
    private final String _apiKey;
    private final String _model;
    private final int _connectTimeoutMs;
    private final int _readTimeoutMs;
    private final int _maxConnectionsTotal;
    private final int _maxConnectionsPerRoute;
    private final int _bulkMaxConcurrency;
    private final int _maxRetries;
    private final long _retryBackoffMs;
    private final long _budgetMaxInputTokens;
    private final long _budgetMaxOutputTokens;
    private final boolean _debugLogging;

    private MuseConnection(String baseUrl, String apiKey, String model,
            int connectTimeoutMs, int readTimeoutMs,
            int maxConnectionsTotal, int maxConnectionsPerRoute,
            int bulkMaxConcurrency, int maxRetries, long retryBackoffMs,
            long budgetMaxInputTokens, long budgetMaxOutputTokens,
            boolean debugLogging) {
        _baseUrl = baseUrl;
        _apiKey = apiKey;
        _model = model;
        _connectTimeoutMs = connectTimeoutMs;
        _readTimeoutMs = readTimeoutMs;
        _maxConnectionsTotal = maxConnectionsTotal;
        _maxConnectionsPerRoute = maxConnectionsPerRoute;
        _bulkMaxConcurrency = bulkMaxConcurrency;
        _maxRetries = maxRetries;
        _retryBackoffMs = retryBackoffMs;
        _budgetMaxInputTokens = budgetMaxInputTokens;
        _budgetMaxOutputTokens = budgetMaxOutputTokens;
        _debugLogging = debugLogging;
    }

    public static MuseConnection from(PropertyMap props) {
        String apiKey = props.getProperty("apiKey", "");
        if (apiKey == null || apiKey.trim().isEmpty()) {
            throw new IllegalArgumentException("The connection has no API key. Open the connection and paste one in.");
        }

        String baseUrl = props.getProperty("baseUrl", "https://api.meta.ai/v1").trim();
        if (baseUrl.endsWith("/")) {
            baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
        }
        try {
            URI uri = URI.create(baseUrl);
            if (uri.getScheme() == null || uri.getHost() == null) {
                throw new IllegalArgumentException("Base URL is not a valid address: " + baseUrl);
            }
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Base URL is not a valid address: " + baseUrl, e);
        }

        return new MuseConnection(
                baseUrl,
                apiKey,
                props.getProperty("model", "muse-spark-1.3").trim(),
                getInt(props, "connectTimeoutMs", 10000),
                getInt(props, "readTimeoutMs", 120000),
                getInt(props, "maxConnectionsTotal", 64),
                getInt(props, "maxConnectionsPerRoute", 32),
                getInt(props, "bulkMaxConcurrency", 8),
                Math.max(0, getInt(props, "maxRetries", 2)),
                Math.max(0L, getLong(props, "retryBackoffMs", 1000L)),
                Math.max(0L, getLong(props, "budgetMaxInputTokens", 0L)),
                Math.max(0L, getLong(props, "budgetMaxOutputTokens", 0L)),
                getBoolean(props, "debugLogging", false));
    }

    /** Identifies the pool configuration, so a changed setting rebuilds the pool. */
    String poolSignature() {
        return _maxConnectionsTotal + "/" + _maxConnectionsPerRoute
                + "/" + _connectTimeoutMs + "/" + _readTimeoutMs;
    }

    private static int getInt(PropertyMap props, String key, int fallback) {
        String value = props.getProperty(key, "");
        if (value == null || value.trim().isEmpty()) {
            return fallback;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static boolean getBoolean(PropertyMap props, String key, boolean fallback) {
        String value = props.getProperty(key, "");
        if (value == null || value.trim().isEmpty()) {
            return fallback;
        }
        return Boolean.parseBoolean(value.trim());
    }

    private static long getLong(PropertyMap props, String key, long fallback) {
        String value = props.getProperty(key, "");
        if (value == null || value.trim().isEmpty()) {
            return fallback;
        }
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    public String getBaseUrl() {
        return _baseUrl;
    }

    public String getApiKey() {
        return _apiKey;
    }

    public String getModel() {
        return _model;
    }

    public int getConnectTimeoutMs() {
        return _connectTimeoutMs;
    }

    public int getReadTimeoutMs() {
        return _readTimeoutMs;
    }

    public int getMaxConnectionsTotal() {
        return _maxConnectionsTotal;
    }

    public int getMaxConnectionsPerRoute() {
        return _maxConnectionsPerRoute;
    }

    public int getBulkMaxConcurrency() {
        return _bulkMaxConcurrency;
    }

    public int getMaxRetries() {
        return _maxRetries;
    }

    public long getRetryBackoffMs() {
        return _retryBackoffMs;
    }

    public long getBudgetMaxInputTokens() {
        return _budgetMaxInputTokens;
    }

    public long getBudgetMaxOutputTokens() {
        return _budgetMaxOutputTokens;
    }

    public boolean isDebugLogging() {
        return _debugLogging;
    }
}
