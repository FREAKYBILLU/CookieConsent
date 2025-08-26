package com.example.scanner.dto;

import jakarta.validation.constraints.NotBlank;

public class CookieUpdateRequest {

    @NotBlank(message = "Cookie name is required")
    private String cookieName;

    private String category;

    private String description;

    public CookieUpdateRequest() {
    }

    public CookieUpdateRequest(String cookieName, String category, String description) {
        this.cookieName = cookieName;
        this.category = category;
        this.description = description;
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
}