package com.atm.intellimate.gateway.http;

import com.atm.intellimate.core.exception.ErrorCode;
import com.atm.intellimate.core.exception.IntelliMateException;
import com.atm.intellimate.gateway.dto.ApiResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(IntelliMateException.class)
    public ResponseEntity<ApiResponse<Void>> handleIntelliMateException(IntelliMateException ex) {
        ErrorCode errorCode = resolveErrorCode(ex.getErrorCode());
        int status = errorCode != null ? errorCode.getHttpStatus() : 500;
        String code = errorCode != null ? errorCode.getCode() : ex.getErrorCode();
        log.warn("Business error: code={}, message={}", code, ex.getMessage());
        return ResponseEntity.status(status)
                .body(ApiResponse.fail(code, ex.getMessage()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiResponse<Void>> handleIllegalArgument(IllegalArgumentException ex) {
        log.warn("Validation error: {}", ex.getMessage());
        return ResponseEntity.badRequest()
                .body(ApiResponse.fail(ErrorCode.VALIDATION_FAILED.getCode(), ex.getMessage()));
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<ApiResponse<Void>> handleResponseStatusException(ResponseStatusException ex) {
        return ResponseEntity.status(ex.getStatusCode())
                .body(ApiResponse.fail("HTTP_" + ex.getStatusCode().value(),
                        ex.getReason() != null ? ex.getReason() : ex.getMessage()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleGenericException(Exception ex) {
        log.error("Unexpected error: {}", ex.getMessage(), ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponse.fail(ErrorCode.INTERNAL_ERROR.getCode(),
                        "An internal error occurred. Please try again later."));
    }

    private ErrorCode resolveErrorCode(String code) {
        if (code == null) return null;
        for (ErrorCode ec : ErrorCode.values()) {
            if (ec.getCode().equals(code)) return ec;
        }
        return null;
    }
}
