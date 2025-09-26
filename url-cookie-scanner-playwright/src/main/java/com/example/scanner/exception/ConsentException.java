package com.example.scanner.exception;

import com.example.scanner.constants.ErrorCodes;

public class ConsentException extends Exception {
    private final String errorCode;

    public ConsentException(String errorCode) {
        super(errorCode);
        this.errorCode = errorCode;
    }

    public String getErrorCode() {
        return errorCode;
    }
}