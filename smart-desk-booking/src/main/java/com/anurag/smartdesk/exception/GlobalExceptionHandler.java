package com.anurag.smartdesk.exception;

import com.anurag.smartdesk.dto.response.ErrorResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.HashMap;
import java.util.Map;

// Centralized exception handler for the entire application.
//
// Why @RestControllerAdvice?
// Without this, every controller would need its own try-catch blocks, leading to
// duplicated error handling logic and inconsistent response formats. This single
// class intercepts ALL exceptions thrown by any controller and converts them into
// our standardized ErrorResponse format.
//
// The handler is ordered from most specific to most general:
//   1. Our custom DomainExceptions (business rule violations)
//   2. Spring's validation exceptions (@Valid failures)
//   3. Catch-all for unexpected errors (prevents raw stack traces from leaking)
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    // Handles all our custom business exceptions (QuotaExceededException,
    // AlreadyBookedException, ResourceNotFoundException, etc.)
    // Each DomainException carries its own HTTP status and error code.
    @ExceptionHandler(DomainException.class)
    public ResponseEntity<ErrorResponse> handleDomainException(
            DomainException ex, HttpServletRequest request) {

        log.warn("Business rule violation: {} | Path: {} | ErrorCode: {}",
                ex.getMessage(), request.getRequestURI(), ex.getErrorCode());

        ErrorResponse response = new ErrorResponse(
                ex.getHttpStatus().value(),
                ex.getErrorCode(),
                ex.getMessage(),
                request.getRequestURI()
        );
        return new ResponseEntity<>(response, ex.getHttpStatus());
    }

    // Handles @Valid annotation failures on request DTOs.
    // Example: If BookingRequest has @NotNull on floorId and the client sends null,
    // this returns a 400 with a map of field-level errors like:
    //   { "floorId": "Floor ID cannot be null", "bookingDate": "must not be null" }
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidationException(
            MethodArgumentNotValidException ex, HttpServletRequest request) {

        Map<String, String> fieldErrors = new HashMap<>();
        ex.getBindingResult().getFieldErrors().forEach(error ->
                fieldErrors.put(error.getField(), error.getDefaultMessage())
        );

        log.warn("Validation failed: {} | Path: {}", fieldErrors, request.getRequestURI());

        ErrorResponse response = new ErrorResponse(
                HttpStatus.BAD_REQUEST.value(),
                "VALIDATION_FAILED",
                "Validation failed for request",
                request.getRequestURI()
        );
        response.setFieldErrors(fieldErrors);
        return new ResponseEntity<>(response, HttpStatus.BAD_REQUEST);
    }

    // Catch-all handler for any unexpected exception we didn't anticipate.
    // This is critical for two reasons:
    //   1. Security: Never expose internal stack traces, SQL errors, or class names
    //      to API consumers.
    //   2. Debugging: Log the full exception server-side so developers can investigate.
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpectedException(
            Exception ex, HttpServletRequest request) {

        log.error("Unexpected error at {}: {}", request.getRequestURI(), ex.getMessage(), ex);

        ErrorResponse response = new ErrorResponse(
                HttpStatus.INTERNAL_SERVER_ERROR.value(),
                "INTERNAL_ERROR",
                "An unexpected error occurred. Please try again later.",
                request.getRequestURI()
        );
        return new ResponseEntity<>(response, HttpStatus.INTERNAL_SERVER_ERROR);
    }
}
