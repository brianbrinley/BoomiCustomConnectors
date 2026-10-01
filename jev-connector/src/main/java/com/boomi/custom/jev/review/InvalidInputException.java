package com.boomi.custom.jev.review;

/**
 * A document or configuration problem detected before calling JEV. Reported as an application error on the
 * document rather than failing the whole execution.
 */
public class InvalidInputException extends Exception {

    private static final long serialVersionUID = 1L;

    public InvalidInputException(String message) {
        super(message);
    }

    public InvalidInputException(String message, Throwable cause) {
        super(message, cause);
    }
}
