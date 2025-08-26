package com.example.scanner.exception;

public class ScanExecutionException extends ScannerException {
    public ScanExecutionException(String message, Throwable cause) {
        super("SCAN_EXECUTION_ERROR", message, "An error occurred during website scanning", cause);
    }

    public ScanExecutionException(String message) {
        super("SCAN_EXECUTION_ERROR", message, "An error occurred during website scanning");
    }
}