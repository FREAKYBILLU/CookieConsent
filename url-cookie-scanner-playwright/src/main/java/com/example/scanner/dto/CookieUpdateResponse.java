package com.example.scanner.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;

import java.time.Instant;

@Data
public class CookieUpdateResponse {

    private String transactionId;
    private String cookieName; // Keep as cookieName for response consistency
    private String category;
    private String description;

    // NEW FIELDS in response
    private String domain;
    private String privacyPolicyUrl;
    @JsonFormat(shape = JsonFormat.Shape.STRING, timezone = "UTC")
    private Instant expires;

    private boolean updated;
    private String message;

    public CookieUpdateResponse() {
    }

    // Updated constructor with new fields
    public CookieUpdateResponse(String transactionId, String cookieName, String category,
                                String description, String domain, String privacyPolicyUrl,
                                Instant expires, boolean updated, String message) {
        this.transactionId = transactionId;
        this.cookieName = cookieName;
        this.category = category;
        this.description = description;
        this.domain = domain;
        this.privacyPolicyUrl = privacyPolicyUrl;
        this.expires = expires;
        this.updated = updated;
        this.message = message;
    }

    // Backward compatibility constructor
    public CookieUpdateResponse(String transactionId, String cookieName, String category,
                                String description, boolean updated, String message) {
        this(transactionId, cookieName, category, description, null, null, null, updated, message);
    }

    // Success response with all fields
    public static CookieUpdateResponse success(String transactionId, String cookieName,
                                               String category, String description, String domain,
                                               String privacyPolicyUrl, Instant expires) {
        return new CookieUpdateResponse(transactionId, cookieName, category, description,
                domain, privacyPolicyUrl, expires, true, "Cookie updated successfully");
    }

    // Backward compatibility success response
    public static CookieUpdateResponse success(String transactionId, String cookieName,
                                               String category, String description) {
        return success(transactionId, cookieName, category, description, null, null, null);
    }

    // Failure response
    public static CookieUpdateResponse failure(String transactionId, String cookieName, String message) {
        return new CookieUpdateResponse(transactionId, cookieName, null, null, null, null, null, false, message);
    }
}