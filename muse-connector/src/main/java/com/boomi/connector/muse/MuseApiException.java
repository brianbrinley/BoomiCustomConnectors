package com.boomi.connector.muse;

/**
 * A failure reported by the Muse API itself (HTTP 4xx/5xx), as opposed to a
 * transport failure. The status code decides how the operation reacts: 401/403
 * invalidates the whole execution, anything else is recorded against the
 * document that caused it.
 */
public class MuseApiException extends Exception {

    private static final long serialVersionUID = 1L;

    private final int _statusCode;

    public MuseApiException(int statusCode, String message) {
        super(message);
        _statusCode = statusCode;
    }

    public MuseApiException(String message, Throwable cause) {
        super(message, cause);
        _statusCode = -1;
    }

    public int getStatusCode() {
        return _statusCode;
    }

    public boolean isAuthFailure() {
        return _statusCode == 401 || _statusCode == 403;
    }

    public boolean isRateLimited() {
        return _statusCode == 429;
    }

    public boolean isServerError() {
        return _statusCode >= 500 && _statusCode < 600;
    }
}
