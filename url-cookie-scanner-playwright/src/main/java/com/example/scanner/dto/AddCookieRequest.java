package com.example.scanner.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import org.hibernate.validator.constraints.URL;
import lombok.Data;

import java.time.Instant;

@Data
public class AddCookieRequest {

    @NotBlank(message = "Cookie name is required and cannot be empty or whitespace")
    private String name;

    @NotBlank(message = "Cookie domain is required and cannot be empty or whitespace")
    private String domain;

    @NotBlank(message = "Path is required and cannot be empty or whitespace")
    private String path = "/";

    private Instant expires;

    private boolean secure = false;

    private boolean httpOnly = false;

    @Pattern(regexp = "^(LAX|STRICT|NONE)$", message = "SameSite must be LAX, STRICT, or NONE")
    private String sameSite = "LAX";

    @Pattern(regexp = "^(FIRST_PARTY|THIRD_PARTY)$", message = "Source must be FIRST_PARTY or THIRD_PARTY")
    private String source = "FIRST_PARTY";

    @NotBlank(message = "Category is required and cannot be empty or whitespace")
    private String category;

    @NotBlank(message = "Description is required and cannot be empty or whitespace")
    private String description;

    @Pattern(regexp = "^(?!\\s*$).+", message = "Description GPT cannot contain only whitespace")
    private String description_gpt;

    @NotBlank(message = "Subdomain name is required and cannot be empty or whitespace")
    private String subdomainName = "main";

    @URL(message = "Privacy policy URL must be a valid URL")
    private String privacyPolicyUrl;

    // NEW FIELD
    private String provider;

}