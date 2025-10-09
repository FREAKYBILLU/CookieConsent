package com.example.scanner.exception;

import java.time.Instant;

public class ConsentHandleExpiredException extends RuntimeException {
    private final String errorCode;
    private final String details;

    public ConsentHandleExpiredException(String errorCode, String message, String details) {
        super(message);
        this.errorCode = errorCode;
        this.details = details;
    }

    public String getErrorCode() {
        return errorCode;
    }

    public String getDetails() {
        return details;
    }
}