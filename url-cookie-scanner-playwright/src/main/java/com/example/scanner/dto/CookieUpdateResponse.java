package com.example.scanner.dto;


import lombok.Data;

@Data
public class CookieUpdateResponse {

    private String transactionId;
    private String cookieName;
    private String category;
    private String description;
    private boolean updated;
    private String message;

    public CookieUpdateResponse() {
    }

    public CookieUpdateResponse(String transactionId, String cookieName, String category,
                                String description, boolean updated, String message) {
        this.transactionId = transactionId;
        this.cookieName = cookieName;
        this.category = category;
        this.description = description;
        this.updated = updated;
        this.message = message;
    }

    // Success response
    public static CookieUpdateResponse success(String transactionId, String cookieName,
                                               String category, String description) {
        return new CookieUpdateResponse(transactionId, cookieName, category, description,
                true, "Cookie updated successfully");
    }

    // Failure response
    public static CookieUpdateResponse failure(String transactionId, String cookieName, String message) {
        return new CookieUpdateResponse(transactionId, cookieName, null, null, false, message);
    }

    
}