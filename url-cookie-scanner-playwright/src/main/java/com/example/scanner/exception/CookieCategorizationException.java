package com.example.scanner.exception;

public class CookieCategorizationException extends ScannerException {
    public CookieCategorizationException(String message, Throwable cause) {
        super("CATEGORIZATION_ERROR", message, "Failed to categorize cookies", cause);
    }

    public CookieCategorizationException(String message) {
        super("CATEGORIZATION_ERROR", message, "Failed to categorize cookies");
    }
}