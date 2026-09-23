package com.anurag.smartdesk.exception;

import org.springframework.http.HttpStatus;

// Thrown when a check-in attempt is invalid.
// Examples:
//   - Check-in deadline has already expired (employee arrived too late)
//   - Booking is not in BOOKED status (already checked in, cancelled, or no-show)
// HTTP 400 Bad Request
public class InvalidCheckInException extends DomainException {

    public InvalidCheckInException(String message) {
        super(message, HttpStatus.BAD_REQUEST, "INVALID_CHECK_IN");
    }
}
