package com.example.scanner.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
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

}