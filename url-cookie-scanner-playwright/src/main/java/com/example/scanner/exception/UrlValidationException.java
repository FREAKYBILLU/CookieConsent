package com.example.scanner.exception;

import com.example.scanner.constants.ErrorCodes;

public class UrlValidationException extends ScannerException {
    public UrlValidationException(String message) {
        super(ErrorCodes.VALIDATION_ERROR, message, "Invalid URL provided");
    }

    public UrlValidationException(String message, Throwable cause) {
        super(ErrorCodes.VALIDATION_ERROR, message, "Invalid URL provided", cause);
    }
}