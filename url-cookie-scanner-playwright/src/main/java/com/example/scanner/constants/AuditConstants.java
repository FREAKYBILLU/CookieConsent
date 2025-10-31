package com.example.scanner.constants;

public class AuditConstants {

    // Groups
    public static final String GROUP_COOKIE_CONSENT = "Cookie Consent";

    // Components
    public static final String COMPONENT_COOKIE_SCAN = "Cookie Scan Flow";
    public static final String COMPONENT_TEMPLATE_CREATION = "Template Creation Flow";
    public static final String COMPONENT_TEMPLATE_UPDATE = "Template Update Flow";
    public static final String COMPONENT_CONSENT_HANDLE = "Consent Handle Flow";
    public static final String COMPONENT_CONSENT_CREATION = "Consent Creation Flow";
    public static final String COMPONENT_CONSENT_UPDATE = "Consent Update Flow";
    public static final String COMPONENT_TOKEN_VERIFICATION = "Token Verification Flow";

    // Action Types - Cookie Scan
    public static final String ACTION_SCAN_INITIATED = "SCAN_INITIATED";
    public static final String ACTION_SCAN_STARTED = "SCAN_STARTED";
    public static final String ACTION_SCAN_FAILED = "SCAN_FAILED";

    // Action Types - Template
    public static final String ACTION_TEMPLATE_CREATION_INITIATED = "TEMPLATE_CREATION_INITIATED";
    public static final String ACTION_TEMPLATE_CREATED = "TEMPLATE_CREATED";
    public static final String ACTION_TEMPLATE_UPDATE_INITIATED = "TEMPLATE_UPDATE_INITIATED";
    public static final String ACTION_NEW_TEMPLATE_VERSION_CREATED = "NEW_TEMPLATE_VERSION_CREATED";

    // Action Types - Consent Handle
    public static final String ACTION_CONSENT_HANDLE_CREATION_INITIATED = "CONSENT_HANDLE_CREATION_INITIATED";
    public static final String ACTION_CONSENT_HANDLE_CREATED = "CONSENT_HANDLE_CREATED";
    public static final String ACTION_CONSENT_HANDLE_MARKED_USED = "CONSENT_HANDLE_MARKED_USED";
    public static final String ACTION_CONSENT_HANDLE_MARKED_USED_AFTER_UPDATE = "CONSENT_HANDLE_MARKED_USED_AFTER_UPDATE";

    // Action Types - Consent
    public static final String ACTION_CONSENT_CREATION_INITIATED = "CONSENT_CREATION_INITIATED";
    public static final String ACTION_CONSENT_CREATED = "CONSENT_CREATED";
    public static final String ACTION_CONSENT_UPDATE_INITIATED = "CONSENT_UPDATE_INITIATED";
    public static final String ACTION_NEW_CONSENT_VERSION_CREATED = "NEW_CONSENT_VERSION_CREATED";
    public static final String ACTION_OLD_CONSENT_VERSION_MARKED_UPDATED = "OLD_CONSENT_VERSION_MARKED_UPDATED";

    // Action Types - Token
    public static final String ACTION_TOKEN_VERIFICATION_INITIATED = "TOKEN_VERIFICATION_INITIATED";
    public static final String ACTION_TOKEN_SIGNATURE_VERIFIED = "TOKEN_SIGNATURE_VERIFIED";
    public static final String ACTION_TOKEN_VALIDATION_SUCCESS = "TOKEN_VALIDATION_SUCCESS";
    public static final String ACTION_TOKEN_VALIDATION_FAILED = "TOKEN_VALIDATION_FAILED";

    // Initiators
    public static final String INITIATOR_DF = "DF";
    public static final String INITIATOR_USER = "USER";
    public static final String INITIATOR_DP = "DP";

    // Resource Types
    public static final String RESOURCE_COOKIE_SCAN = "Cookie Scan";
    public static final String RESOURCE_COOKIE_TEMPLATE = "Cookie Template";
    public static final String RESOURCE_CONSENT_HANDLE = "Consent Handle";
    public static final String RESOURCE_CONSENT = "Consent";
    public static final String RESOURCE_TOKEN = "Token";

    // Actor Types
    public static final String ACTOR_TYPE_SYSTEM = "SYSTEM";
}