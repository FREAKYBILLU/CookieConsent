package com.example.scanner.constants;

public class ErrorCodes {
    // 4xx Client Errors


    public static final String EMPTY_ERROR = "R1001";                    // Empty/null fields
    public static final String INVALID_FORMAT_ERROR = "R1002";           // Invalid format
    public static final String INVALID_STATE_ERROR = "R1003";            // Business rule violations
    public static final String DUPLICATE_ERROR = "R1004";                // Duplicate entries
    public static final String VALIDATION_ERROR = "R4001";               // Generic validation
    public static final String TRANSACTION_NOT_FOUND = "R4004";          // Transaction not found
    public static final String COOKIE_NOT_FOUND = "R4041";               // Cookie not found
    public static final String NO_COOKIES_FOUND = "R4042";               // No cookies in transaction
    public static final String SCAN_EXECUTION_ERROR = "R5002";
    public static final String NOT_FOUND = "R4041";
    public static final String METHOD_NOT_ALLOWED = "R4051";
    public static final String INTERNAL_ERROR = "R5001";
    public static final String EXTERNAL_SERVICE_ERROR = "R5003";
    public static final String CATEGORIZATION_ERROR = "R5004";
}