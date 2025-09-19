package com.example.scanner.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import org.hibernate.validator.constraints.URL;

import java.time.Instant;

@Data
public class CookieUpdateRequest {

    @NotBlank(message = "Cookie name is required and cannot be empty or whitespace")
    private String name; // CHANGED: was "cookieName", now "name"

    @NotBlank(message = "Category is required and cannot be empty or whitespace")
    private String category;

    @NotBlank(message = "Description is required and cannot be empty or whitespace")
    private String description;

    // NEW FIELDS - matching CookieDto structure
    private String domain;

    @URL(message = "Privacy policy URL must be a valid URL")
    private String privacyPolicyUrl;

    @JsonFormat(shape = JsonFormat.Shape.STRING, timezone = "UTC")
    private Instant expires;

    public CookieUpdateRequest() {
    }

    // Updated constructor with new fields
    public CookieUpdateRequest(String name, String category, String description, String domain,
                               String privacyPolicyUrl, Instant expires) {
        this.name = name;
        this.category = category;
        this.description = description;
        this.domain = domain;
        this.privacyPolicyUrl = privacyPolicyUrl;
        this.expires = expires;
    }

    // Backward compatibility constructor
    public CookieUpdateRequest(String name, String category, String description) {
        this(name, category, description, null, null, null);
    }
}