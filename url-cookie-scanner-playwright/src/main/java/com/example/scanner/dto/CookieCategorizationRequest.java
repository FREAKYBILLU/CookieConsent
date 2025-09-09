package com.example.scanner.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import lombok.Getter;

import java.util.List;

/**
 * Request DTO for cookie categorization API
 */
@Data
public class CookieCategorizationRequest {
    @JsonProperty("cookie_names")
    private List<String> cookieNames;

    public CookieCategorizationRequest() {
    }

    public CookieCategorizationRequest(List<String> cookieNames) {
        this.cookieNames = cookieNames;
    }

}