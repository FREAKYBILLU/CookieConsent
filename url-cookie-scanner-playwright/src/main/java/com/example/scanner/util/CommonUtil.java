package com.example.scanner.util;

import java.util.regex.Pattern;

public class CommonUtil {

    private static final Pattern VALID_TRANSACTION_ID = Pattern.compile("^[a-fA-F0-9]{8}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{12}$");

    public static  boolean isValidTransactionId(String transactionId) {
        if (transactionId == null || transactionId.trim().isEmpty()) {
            return false;
        }

        if (transactionId.contains("..") ||      // Basic path traversal (../../../etc/passwd)
                transactionId.contains("/") ||       // Forward slash (shouldn't be in UUID)
                transactionId.contains("\\") ||      // Backslash (Windows path traversal)
                transactionId.contains("%2e") ||     // URL-encoded dot (encoded ..)
                transactionId.contains("%2f") ||     // URL-encoded forward slash (encoded /)
                transactionId.contains("%5c")) {     // URL-encoded backslash (encoded \)
            return false;
        }

        // Validate UUID format - your app generates UUIDs like: 550e8400-e29b-41d4-a716-446655440000
        // This regex ensures ONLY valid UUIDs are accepted (36 characters, specific pattern)
        return VALID_TRANSACTION_ID.matcher(transactionId).matches();
    }
}
