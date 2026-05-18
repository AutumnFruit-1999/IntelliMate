package com.atm.intellimate.core.exception;

/**
 * Base exception for all IntelliMate-specific errors.
 */
public class IntelliMateException extends RuntimeException {

    private final String errorCode;

    public IntelliMateException(String errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public IntelliMateException(String errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }

    public String getErrorCode() {
        return errorCode;
    }
}
