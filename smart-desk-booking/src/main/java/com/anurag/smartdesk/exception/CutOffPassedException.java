package com.anurag.smartdesk.exception;

import org.springframework.http.HttpStatus;

// Thrown when a cancellation or modification is attempted after the cut-off time.
// Example: Trying to cancel a booking after its check-in deadline has passed.
// HTTP 400 Bad Request
public class CutOffPassedException extends DomainException {

    public CutOffPassedException(String message) {
        super(message, HttpStatus.BAD_REQUEST, "CUTOFF_PASSED");
    }
}
