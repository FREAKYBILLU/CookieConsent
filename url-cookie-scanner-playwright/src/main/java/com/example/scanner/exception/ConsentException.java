package com.example.scanner.exception;

import com.example.scanner.constants.ErrorCodes;

public class ConsentException extends Exception {
    private final ErrorCodes errorCode;

    public ConsentException(ErrorCodes errorCode) {
        super(errorCode.getMessage());
        this.errorCode = errorCode;
    }

    public ErrorCodes getErrorCode() {
        return errorCode;
    }
}