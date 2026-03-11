package com.atm.javaclaw.core.exception;

/**
 * Base exception for all JavaClaw-specific errors.
 */
public class JavaClawException extends RuntimeException {

    private final String errorCode;

    public JavaClawException(String errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public JavaClawException(String errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }

    public String getErrorCode() {
        return errorCode;
    }
}
