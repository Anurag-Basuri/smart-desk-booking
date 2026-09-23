package com.anurag.smartdesk.exception;

import org.springframework.http.HttpStatus;

// Thrown when a requested entity (floor, desk, employee, booking) does not exist.
// Example: "Floor with ID 99 not found"
// HTTP 404 Not Found
public class ResourceNotFoundException extends DomainException {

    public ResourceNotFoundException(String message) {
        super(message, HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND");
    }
}
