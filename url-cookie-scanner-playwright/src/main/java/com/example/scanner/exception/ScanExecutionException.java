package com.example.scanner.exception;

import com.example.scanner.constants.ErrorCodes;

public class ScanExecutionException extends ScannerException {
    public ScanExecutionException(String message, Throwable cause) {
        super(ErrorCodes.INTERNAL_ERROR, message, "An error occurred during website scanning", cause);
    }

    public ScanExecutionException(String message) {
        super(ErrorCodes.INTERNAL_ERROR, message, "An error occurred during website scanning");
    }
}