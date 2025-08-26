package com.example.scanner.exception;

public class UrlValidationException extends ScannerException {
    public UrlValidationException(String message) {
        super("URL_VALIDATION_ERROR", message, "The provided URL is invalid or cannot be scanned");
    }

    public UrlValidationException(String message, Throwable cause) {
        super("URL_VALIDATION_ERROR", message, "The provided URL is invalid or cannot be scanned", cause);
    }
}