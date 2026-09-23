package com.anurag.smartdesk.exception;

import org.springframework.http.HttpStatus;

// Thrown when a specific desk is already booked by someone else for that date.
// This acts as the application-level guard; the partial unique index
// uq_active_desk_day serves as the storage-level safety net.
// HTTP 409 Conflict
public class DeskAlreadyBookedException extends DomainException {

    public DeskAlreadyBookedException(String message) {
        super(message, HttpStatus.CONFLICT, "DESK_ALREADY_BOOKED");
    }
}
