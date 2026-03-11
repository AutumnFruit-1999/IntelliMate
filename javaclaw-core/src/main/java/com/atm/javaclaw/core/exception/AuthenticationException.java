package com.atm.javaclaw.core.exception;

public class AuthenticationException extends JavaClawException {

    public AuthenticationException(String message) {
        super("AUTH_ERROR", message);
    }

    public AuthenticationException(String message, Throwable cause) {
        super("AUTH_ERROR", message, cause);
    }
}
