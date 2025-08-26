package com.example.scanner.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * Request DTO for cookie categorization API
 */
public class CookieCategorizationRequest {
    @JsonProperty("cookie_names")
    private List<String> cookieNames;

    public CookieCategorizationRequest() {
    }

    public CookieCategorizationRequest(List<String> cookieNames) {
        this.cookieNames = cookieNames;
    }

    public List<String> getCookieNames() {
        return cookieNames;
    }

    public void setCookieNames(List<String> cookieNames) {
        this.cookieNames = cookieNames;
    }
}