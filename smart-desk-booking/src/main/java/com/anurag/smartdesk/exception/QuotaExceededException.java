package com.anurag.smartdesk.exception;

import org.springframework.http.HttpStatus;

// Thrown when a team's hot desk booking would exceed its allocated quota on a floor.
// Example: "Team Alpha has reached its limit of 8 hot desks on Floor 3"
// HTTP 422 Unprocessable Entity (semantically valid request, but violates business rule)
public class QuotaExceededException extends DomainException {

    public QuotaExceededException(String message) {
        super(message, HttpStatus.UNPROCESSABLE_ENTITY, "QUOTA_EXCEEDED");
    }
}
