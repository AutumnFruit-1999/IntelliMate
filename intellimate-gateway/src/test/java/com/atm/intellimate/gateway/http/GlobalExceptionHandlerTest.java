package com.atm.intellimate.gateway.http;

import com.atm.intellimate.core.exception.ErrorCode;
import com.atm.intellimate.core.exception.IntelliMateException;
import com.atm.intellimate.gateway.dto.ApiResponse;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThat;

class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void handleIntelliMateException_returnsCorrectStatusAndCode() {
        var ex = new IntelliMateException(ErrorCode.AGENT_NOT_FOUND);
        ResponseEntity<ApiResponse<Void>> response = handler.handleIntelliMateException(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().success()).isFalse();
        assertThat(response.getBody().error().code()).isEqualTo("AGENT_001");
    }

    @Test
    void handleIllegalArgument_returns400() {
        var ex = new IllegalArgumentException("bad param");
        ResponseEntity<ApiResponse<Void>> response = handler.handleIllegalArgument(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().error().code()).isEqualTo("VALIDATION_001");
    }

    @Test
    void handleResponseStatusException_preservesStatus() {
        var ex = new ResponseStatusException(HttpStatus.CONFLICT, "already exists");
        ResponseEntity<ApiResponse<Void>> response = handler.handleResponseStatusException(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void handleGenericException_returns500WithSanitizedMessage() {
        var ex = new RuntimeException("internal DB connection password=secret123");
        ResponseEntity<ApiResponse<Void>> response = handler.handleGenericException(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody().error().code()).isEqualTo("INTERNAL_001");
        assertThat(response.getBody().error().message()).doesNotContain("secret123");
    }
}
