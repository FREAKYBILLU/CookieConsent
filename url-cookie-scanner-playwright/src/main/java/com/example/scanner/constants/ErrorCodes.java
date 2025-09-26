package com.example.scanner.constants;

/**
 * Comprehensive error codes for the consent management system
 * Organized by functional area with clear naming conventions
 */
public final class ErrorCodes {

    // ==== GENERAL ERROR CODES ====
    public static final String VALIDATION_ERROR = "R4001";
    public static final String NOT_FOUND = "R4041";
    public static final String INTERNAL_ERROR = "R5001";
    public static final String DUPLICATE_ERROR = "R1004";
    public static final String BUSINESS_RULE_VIOLATION = "R4002"; // Changed from R4001

    // ==== COOKIE AND SCAN RELATED ERRORS ====
    public static final String EMPTY_ERROR = "R1001";
    public static final String INVALID_FORMAT_ERROR = "R1002";
    public static final String INVALID_STATE_ERROR = "R1003";
    public static final String TRANSACTION_NOT_FOUND = "R4004";
    public static final String COOKIE_NOT_FOUND = "R4043"; // Changed from R4041 to avoid conflict
    public static final String NO_COOKIES_FOUND = "R4042";
    public static final String SCAN_EXECUTION_ERROR = "R5002";
    public static final String METHOD_NOT_ALLOWED = "R4051";
    public static final String EXTERNAL_SERVICE_ERROR = "R5003";
    public static final String CATEGORIZATION_ERROR = "R5004";

    // ==== TEMPLATE ERRORS ====
    public static final String TEMPLATE_NOT_FOUND = "JCMP2002";
    public static final String TEMPLATE_NAME_REQUIRED = "JCMP1010";
    public static final String BUSINESS_ID_REQUIRED = "JCMP1011";
    public static final String SCAN_ID_REQUIRED = "JCMP1032";
    public static final String TEMPLATE_NAME_EXISTS = "JCMP2001";
    public static final String SCAN_NOT_COMPLETED = "JCMP3002";
    public static final String TEMPLATE_EXISTS_FOR_SCAN = "JCMP2007";
    public static final String INVALID_TEMPLATE_STATUS = "JCMP1033";

    // ==== TEMPLATE VERSIONING ERRORS ====
    public static final String TEMPLATE_NOT_UPDATABLE = "JCMP4001";
    public static final String TEMPLATE_VERSION_NOT_FOUND = "JCMP4002";
    public static final String TEMPLATE_NO_ACTIVE_VERSION = "JCMP4003";
    public static final String TEMPLATE_MULTIPLE_ACTIVE_VERSIONS = "JCMP4004";
    public static final String TEMPLATE_UPDATE_DRAFT_NOT_ALLOWED = "JCMP4005";
    public static final String TEMPLATE_VERSION_CONFLICT = "JCMP4007";

    // ==== CONSENT HANDLE ERRORS ====
    public static final String CONSENT_HANDLE_NOT_FOUND = "JCMP3003";
    public static final String CONSENT_HANDLE_ALREADY_USED = "JCMP3004";
    public static final String CONSENT_HANDLE_EXPIRED = "JCMP3005";

    // ==== CONSENT ERRORS ====
    public static final String CONSENT_NOT_FOUND = "JCMP5001"; // Changed from JCMP5003
    public static final String CONSENT_VERSION_NOT_FOUND = "JCMP5002";
    public static final String CONSENT_NO_ACTIVE_VERSION = "JCMP5003";
    public static final String CONSENT_MULTIPLE_ACTIVE_VERSIONS = "JCMP5004";
    public static final String CONSENT_CANNOT_UPDATE_EXPIRED = "JCMP5005";
    public static final String CONSENT_HANDLE_CUSTOMER_MISMATCH = "JCMP5006";
    public static final String CONSENT_HANDLE_BUSINESS_MISMATCH = "JCMP5007";
    public static final String CONSENT_VERSION_CONFLICT = "JCMP5008";

    // ==== VERSION MANAGEMENT ERRORS ====
    public static final String VERSION_NUMBER_INVALID = "JCMP6001";
    public static final String VERSION_STATUS_TRANSITION_INVALID = "JCMP6002";
    public static final String CONCURRENT_VERSION_CREATION = "JCMP6005";
    public static final String VERSION_INTEGRITY_CHECK_FAILED = "JCMP6006";

    // ==== DATA INTEGRITY ERRORS ====
    public static final String IMMUTABLE_FIELD_MODIFICATION = "JCMP7001";
    public static final String REFERENCE_INTEGRITY_VIOLATION = "JCMP7003";
    public static final String TENANT_ISOLATION_VIOLATION = "JCMP7004";

    // ==== BUSINESS RULE VIOLATIONS ====
    public static final String UPDATE_FREQUENCY_LIMIT_EXCEEDED = "JCMP8001";
    public static final String UPDATE_NOT_ALLOWED_BUSINESS_HOURS = "JCMP8002";
    public static final String INSUFFICIENT_PERMISSIONS = "JCMP8003";

    // ==== VALIDATION SPECIFIC ERRORS ====
    public static final String PREFERENCES_REQUIRED = "JCMP1012";
    public static final String MULTILINGUAL_CONFIG_REQUIRED = "JCMP1013";
    public static final String UI_CONFIG_REQUIRED = "JCMP1014";
    public static final String PURPOSE_IDS_REQUIRED = "JCMP1017";
    public static final String PREFERENCE_VALIDITY_REQUIRED = "JCMP1018";
    public static final String PROCESSOR_ACTIVITY_IDS_REQUIRED = "JCMP1019";

    // Private constructor to prevent instantiation
    private ErrorCodes() {
        throw new UnsupportedOperationException("Utility class cannot be instantiated");
    }

    /**
     * Get human-readable description for error code
     * @param errorCode The error code
     * @return Human-readable description
     */
    public static String getDescription(String errorCode) {
        return switch (errorCode) {
            // General errors
            case VALIDATION_ERROR -> "Validation failed";
            case NOT_FOUND -> "Resource not found";
            case INTERNAL_ERROR -> "Internal server error";
            case DUPLICATE_ERROR -> "Duplicate resource";
            case BUSINESS_RULE_VIOLATION -> "Business rule violation";

            // Scan errors
            case TRANSACTION_NOT_FOUND -> "Transaction not found";
            case SCAN_EXECUTION_ERROR -> "Scan execution error";

            // Template errors
            case TEMPLATE_NOT_FOUND -> "Template not found";
            case TEMPLATE_NAME_REQUIRED -> "Template name is required and cannot be empty";
            case BUSINESS_ID_REQUIRED -> "Business ID is required and cannot be empty";
            case SCAN_ID_REQUIRED -> "Scan ID is required and must be from a completed scan";
            case TEMPLATE_NAME_EXISTS -> "Template name already exists for this tenant";
            case SCAN_NOT_COMPLETED -> "Scan status is not COMPLETED";
            case TEMPLATE_EXISTS_FOR_SCAN -> "Template already exists for this scan ID";
            case INVALID_TEMPLATE_STATUS -> "Template status must be either DRAFT or PUBLISHED";

            // Template versioning
            case TEMPLATE_NOT_UPDATABLE -> "Template not in valid state for update";
            case TEMPLATE_VERSION_NOT_FOUND -> "Template version not found";
            case TEMPLATE_NO_ACTIVE_VERSION -> "Template has no active version";
            case TEMPLATE_MULTIPLE_ACTIVE_VERSIONS -> "Multiple active template versions found";
            case TEMPLATE_UPDATE_DRAFT_NOT_ALLOWED -> "Cannot update draft template - use direct edit instead";
            case TEMPLATE_VERSION_CONFLICT -> "Template version conflict during update";

            // Consent handle errors
            case CONSENT_HANDLE_NOT_FOUND -> "Consent handle not found";
            case CONSENT_HANDLE_ALREADY_USED -> "Consent handle already used";
            case CONSENT_HANDLE_EXPIRED -> "Consent handle expired";

            // Consent errors
            case CONSENT_NOT_FOUND -> "Consent not found";
            case CONSENT_VERSION_NOT_FOUND -> "Consent version not found";
            case CONSENT_NO_ACTIVE_VERSION -> "Consent has no active version";
            case CONSENT_MULTIPLE_ACTIVE_VERSIONS -> "Multiple active consent versions found";
            case CONSENT_CANNOT_UPDATE_EXPIRED -> "Cannot update expired consent";
            case CONSENT_HANDLE_CUSTOMER_MISMATCH -> "Consent handle customer does not match consent customer";
            case CONSENT_HANDLE_BUSINESS_MISMATCH -> "Consent handle business does not match consent business";
            case CONSENT_VERSION_CONFLICT -> "Consent version conflict during update";

            // Version management
            case VERSION_NUMBER_INVALID -> "Invalid version number";
            case VERSION_STATUS_TRANSITION_INVALID -> "Invalid version status transition";
            case CONCURRENT_VERSION_CREATION -> "Concurrent version creation detected";
            case VERSION_INTEGRITY_CHECK_FAILED -> "Version integrity check failed";

            // Data integrity
            case IMMUTABLE_FIELD_MODIFICATION -> "Immutable field modification attempted";
            case REFERENCE_INTEGRITY_VIOLATION -> "Reference integrity violation";
            case TENANT_ISOLATION_VIOLATION -> "Tenant isolation violation";

            // Business rules
            case UPDATE_FREQUENCY_LIMIT_EXCEEDED -> "Update frequency limit exceeded";
            case UPDATE_NOT_ALLOWED_BUSINESS_HOURS -> "Update not allowed during business hours";
            case INSUFFICIENT_PERMISSIONS -> "Insufficient permissions for version operation";

            // Validation specific
            case PREFERENCES_REQUIRED -> "At least one preference is required";
            case MULTILINGUAL_CONFIG_REQUIRED -> "Multilingual configuration is required";
            case UI_CONFIG_REQUIRED -> "UI configuration is required";
            case PURPOSE_IDS_REQUIRED -> "Purpose IDs are required for each preference";
            case PREFERENCE_VALIDITY_REQUIRED -> "Preference validity is required";
            case PROCESSOR_ACTIVITY_IDS_REQUIRED -> "Processor activity IDs are required for each preference";

            default -> "Unknown error";
        };
    }

    /**
     * Check if error code is related to versioning
     * @param errorCode The error code to check
     * @return true if it's a versioning-related error
     */
    public static boolean isVersioningError(String errorCode) {
        return errorCode != null && (
                errorCode.startsWith("JCMP4") ||
                        errorCode.startsWith("JCMP5") ||
                        errorCode.startsWith("JCMP6") ||
                        errorCode.startsWith("JCMP7") ||
                        errorCode.startsWith("JCMP8")
        );
    }

    /**
     * Get error severity level
     * @param errorCode The error code
     * @return Severity level (LOW, MEDIUM, HIGH, CRITICAL)
     */
    public static String getSeverity(String errorCode) {
        if (errorCode == null) return "UNKNOWN";

        return switch (errorCode) {
            // Critical errors (data integrity issues)
            case TEMPLATE_MULTIPLE_ACTIVE_VERSIONS, CONSENT_MULTIPLE_ACTIVE_VERSIONS,
                 REFERENCE_INTEGRITY_VIOLATION, TENANT_ISOLATION_VIOLATION -> "CRITICAL";

            // High severity (security and business rule violations)
            case CONSENT_HANDLE_CUSTOMER_MISMATCH, CONSENT_HANDLE_BUSINESS_MISMATCH,
                 INSUFFICIENT_PERMISSIONS, EXTERNAL_SERVICE_ERROR -> "HIGH";

            // Medium severity (operational issues)
            case TEMPLATE_NOT_UPDATABLE, TEMPLATE_VERSION_CONFLICT, CONSENT_VERSION_CONFLICT,
                 CONCURRENT_VERSION_CREATION, SCAN_EXECUTION_ERROR, CATEGORIZATION_ERROR,
                 INVALID_STATE_ERROR -> "MEDIUM";

            // Low severity (user errors and not found cases)
            case NOT_FOUND, VALIDATION_ERROR, TEMPLATE_NOT_FOUND, CONSENT_NOT_FOUND,
                 CONSENT_HANDLE_NOT_FOUND, TRANSACTION_NOT_FOUND, COOKIE_NOT_FOUND,
                 NO_COOKIES_FOUND, EMPTY_ERROR, INVALID_FORMAT_ERROR -> "LOW";

            default -> "MEDIUM";
        };
    }

    /**
     * Get all template-related error codes
     * @return Array of template error codes
     */
    public static String[] getTemplateErrorCodes() {
        return new String[]{
                TEMPLATE_NOT_FOUND, TEMPLATE_NAME_REQUIRED, BUSINESS_ID_REQUIRED, SCAN_ID_REQUIRED,
                TEMPLATE_NAME_EXISTS, SCAN_NOT_COMPLETED, TEMPLATE_EXISTS_FOR_SCAN, INVALID_TEMPLATE_STATUS,
                TEMPLATE_NOT_UPDATABLE, TEMPLATE_VERSION_NOT_FOUND, TEMPLATE_NO_ACTIVE_VERSION,
                TEMPLATE_MULTIPLE_ACTIVE_VERSIONS, TEMPLATE_UPDATE_DRAFT_NOT_ALLOWED, TEMPLATE_VERSION_CONFLICT
        };
    }

    /**
     * Get all consent-related error codes
     * @return Array of consent error codes
     */
    public static String[] getConsentErrorCodes() {
        return new String[]{
                CONSENT_HANDLE_NOT_FOUND, CONSENT_HANDLE_ALREADY_USED, CONSENT_HANDLE_EXPIRED,
                CONSENT_NOT_FOUND, CONSENT_VERSION_NOT_FOUND, CONSENT_NO_ACTIVE_VERSION,
                CONSENT_MULTIPLE_ACTIVE_VERSIONS, CONSENT_CANNOT_UPDATE_EXPIRED,
                CONSENT_HANDLE_CUSTOMER_MISMATCH, CONSENT_HANDLE_BUSINESS_MISMATCH, CONSENT_VERSION_CONFLICT
        };
    }

    /**
     * Get all versioning-related error codes
     * @return Array of versioning error codes
     */
    public static String[] getVersioningErrorCodes() {
        return new String[]{
                VERSION_NUMBER_INVALID, VERSION_STATUS_TRANSITION_INVALID, CONCURRENT_VERSION_CREATION,
                VERSION_INTEGRITY_CHECK_FAILED, IMMUTABLE_FIELD_MODIFICATION, REFERENCE_INTEGRITY_VIOLATION,
                TENANT_ISOLATION_VIOLATION
        };
    }
}