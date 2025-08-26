package com.example.scanner.exception;

public class TransactionNotFoundException extends ScannerException {
    public TransactionNotFoundException(String transactionId) {
        super("TRANSACTION_NOT_FOUND",
                "Transaction not found: " + transactionId,
                "The requested scan transaction was not found");
    }

    public TransactionNotFoundException(String transactionId, Throwable cause) {
        super("TRANSACTION_NOT_FOUND",
                "Transaction not found: " + transactionId,
                "The requested scan transaction was not found",
                cause);
    }
}