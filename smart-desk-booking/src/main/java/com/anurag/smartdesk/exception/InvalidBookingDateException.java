package com.anurag.smartdesk.exception;

import org.springframework.http.HttpStatus;

// Thrown when a booking request targets an invalid date.
// Examples:
//   - Booking date is in the past
//   - Booking date exceeds the N-business-day advance window
//   - On day D, booking a time window that has already started (requestTime >= cutoffTime)
// HTTP 400 Bad Request
public class InvalidBookingDateException extends DomainException {

    public InvalidBookingDateException(String message) {
        super(message, HttpStatus.BAD_REQUEST, "INVALID_BOOKING_DATE");
    }
}
