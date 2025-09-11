package com.example.scanner.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

import java.time.Instant;

@Data
public class AddCookieRequest {

    @NotBlank(message = "Cookie name is required and cannot be empty")
    private String name;

    @NotBlank(message = "Cookie domain is required and cannot be empty")
    private String domain;

    private String path = "/";

    private Instant expires;

    private boolean secure = false;

    private boolean httpOnly = false;

    @Pattern(regexp = "^(LAX|STRICT|NONE)$", message = "SameSite must be LAX, STRICT, or NONE")
    private String sameSite = "LAX";

    @Pattern(regexp = "^(FIRST_PARTY|THIRD_PARTY)$", message = "Source must be FIRST_PARTY or THIRD_PARTY")
    private String source = "FIRST_PARTY";

    private String category;

    private String description;

    private String description_gpt;

    @NotBlank(message = "Subdomain name is required and cannot be empty")
    private String subdomainName = "main";

    public AddCookieRequest() {
    }

    public AddCookieRequest(String name, String domain, String subdomainName) {
        this.name = name;
        this.domain = domain;
        this.subdomainName = subdomainName;
    }
}