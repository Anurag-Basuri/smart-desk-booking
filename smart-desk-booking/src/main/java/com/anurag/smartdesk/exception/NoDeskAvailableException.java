package com.anurag.smartdesk.exception;

import org.springframework.http.HttpStatus;

// Thrown when all eligible desks on a floor are occupied for the requested date.
// The allocation strategy found zero candidates after filtering out booked desks.
// HTTP 409 Conflict
public class NoDeskAvailableException extends DomainException {

    public NoDeskAvailableException(String message) {
        super(message, HttpStatus.CONFLICT, "NO_DESK_AVAILABLE");
    }
}
