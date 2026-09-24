package com.anurag.smartdesk.exception;

import org.springframework.http.HttpStatus;

public class UnauthorizedOperationException extends DomainException {
    public UnauthorizedOperationException(String message) {
        super(message, HttpStatus.FORBIDDEN, "UNAUTHORIZED_OPERATION");
    }
}
