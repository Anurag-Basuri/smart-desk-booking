package com.anurag.smartdesk.exception;

import lombok.Getter;
import org.springframework.http.HttpStatus;

// Base exception for all domain-specific business rule violations.
//
// Why a base class?
// Every business exception in our system carries two extra pieces of information
// beyond a plain message: an HTTP status code (e.g. 409, 422) and a machine-readable
// error code (e.g. "QUOTA_EXCEEDED"). The GlobalExceptionHandler reads these to
// build a consistent ErrorResponse without needing a separate catch block for
// every exception type.
@Getter
public abstract class DomainException extends RuntimeException {

    private final HttpStatus httpStatus;
    private final String errorCode;

    protected DomainException(String message, HttpStatus httpStatus, String errorCode) {
        super(message);
        this.httpStatus = httpStatus;
        this.errorCode = errorCode;
    }
}
