package com.example.scanner.constants;

public class ErrorCodes {
    // 4xx Client Errors
    public static final String VALIDATION_ERROR = "R4001";
    public static final String TRANSACTION_NOT_FOUND = "R4004";
    public static final String COOKIE_NOT_FOUND = "R4041";
    public static final String METHOD_NOT_ALLOWED = "R4051";

    // 5xx Server Errors
    public static final String INTERNAL_ERROR = "R5001";
    public static final String SCAN_EXECUTION_ERROR = "R5002";
    public static final String EXTERNAL_SERVICE_ERROR = "R5003";
    public static final String CATEGORIZATION_ERROR = "R5004";
}