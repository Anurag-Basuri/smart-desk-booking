package com.anurag.smartdesk.exception;

import org.springframework.http.HttpStatus;

// Thrown when an employee already holds an active booking for the requested date.
// Enforced at application level; backed by the partial unique index
// uq_active_employee_day as storage-level defense-in-depth.
// HTTP 409 Conflict
public class AlreadyBookedException extends DomainException {

    public AlreadyBookedException(String message) {
        super(message, HttpStatus.CONFLICT, "ALREADY_BOOKED");
    }
}
