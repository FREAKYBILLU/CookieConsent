package com.example.scanner.dto;

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

    // Getters and setters
    public String getTransactionId() {
        return transactionId;
    }

    public void setTransactionId(String transactionId) {
        this.transactionId = transactionId;
    }

    public String getCookieName() {
        return cookieName;
    }

    public void setCookieName(String cookieName) {
        this.cookieName = cookieName;
    }

    public String getCategory() {
        return category;
    }

    public void setCategory(String category) {
        this.category = category;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public boolean isUpdated() {
        return updated;
    }

    public void setUpdated(boolean updated) {
        this.updated = updated;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }
}