package com.anurag.smartdesk.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.Map;

// Structured error response returned when any exception occurs.
// API consumers can distinguish different error type using the errorCode
// rather than parsing human-readable messages.
@Getter
@Setter
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ErrorResponse {

    private boolean success = false;
    private int status;
    private String errorCode;
    private String message;
    private String path;
    private Instant timestamp;

    // Field-level validation errors (e.g. "bookingDate": "must be a future date")
    // Only populated for @Valid failures; null otherwise (excluded by @JsonInclude)
    private Map<String, String> fieldErrors;

    public ErrorResponse(int status, String errorCode, String message, String path) {
        this.status = status;
        this.errorCode = errorCode;
        this.message = message;
        this.path = path;
        this.timestamp = Instant.now();
    }
}
