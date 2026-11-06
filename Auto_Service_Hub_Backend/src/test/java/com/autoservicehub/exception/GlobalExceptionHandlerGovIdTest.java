package com.autoservicehub.exception;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import com.autoservicehub.dto.ApiErrorResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the HTTP contract for {@link GovernmentIdKeyNotConfiguredException}.
 *
 * <p>Regression guard for the mechanic-create 500: a Government ID submitted
 * while MECHANIC_GOVID_ENCRYPTION_KEY is unset must surface as an actionable
 * 503, not the generic "Something went wrong" INTERNAL_ERROR that gave an
 * operator nothing to act on.
 */
class GlobalExceptionHandlerGovIdTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    @DisplayName("E1 — missing Government ID key maps to 503 with an actionable code, not a generic 500")
    void missingKey_mapsTo503() {
        String message = "Government ID cannot be stored because the encryption key "
                + "(MECHANIC_GOVID_ENCRYPTION_KEY) is not configured on this server.";

        ResponseEntity<ApiErrorResponse> response =
                handler.handleGovIdKeyNotConfigured(new GovernmentIdKeyNotConfiguredException(message));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getStatusCode()).isNotEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);

        ApiErrorResponse body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.getCode()).isEqualTo("GOV_ID_ENCRYPTION_KEY_NOT_CONFIGURED");
        assertThat(body.getMessage()).contains("MECHANIC_GOVID_ENCRYPTION_KEY");
    }

    @Test
    @DisplayName("E2 — generic handler still returns 500 for unrelated exceptions")
    void unrelatedException_stillMapsTo500() {
        ResponseEntity<?> response = handler.handleGeneric(new IllegalStateException("boom"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
    }
}