package com.anurag.smartdesk.exception;

import org.springframework.http.HttpStatus;

// Thrown when a manual desk selection violates constraints.
// Examples:
//   - Desk does not belong to the requested floor
//   - Desk is currently inactive or maintenance mode
//   - Number of manual desk IDs does not match number of team members
//   - Duplicate desk IDs specified
// HTTP 400 Bad Request
public class InvalidDeskSelectionException extends DomainException {

    public InvalidDeskSelectionException(String message) {
        super(message, HttpStatus.BAD_REQUEST, "INVALID_DESK_SELECTION");
    }

    public InvalidDeskSelectionException(String message, String errorCode) {
        super(message, HttpStatus.BAD_REQUEST, errorCode);
    }
}
