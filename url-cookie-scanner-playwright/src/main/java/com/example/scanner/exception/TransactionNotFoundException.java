package com.example.scanner.exception;

import com.example.scanner.constants.ErrorCodes;

public class TransactionNotFoundException extends ScannerException {
    public TransactionNotFoundException(String transactionId) {
        super(ErrorCodes.TRANSACTION_NOT_FOUND,
                "No scan record exists in database for the provided transaction ID" + transactionId,
                "The requested scan transaction was not found");
    }

    public TransactionNotFoundException(String transactionId, Throwable cause) {
        super(ErrorCodes.TRANSACTION_NOT_FOUND,
                "No scan record exists in database for the provided transaction ID" + transactionId,
                "The requested scan transaction was not found",
                cause);
    }
}