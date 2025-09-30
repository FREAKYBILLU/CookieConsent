package com.example.scanner.dto.response;

import com.example.scanner.dto.CustomerIdentifiers;
import com.example.scanner.dto.Multilingual;
import com.example.scanner.enums.Status;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
@Schema(description = "Response after successfully creating a consent")
public class ConsentCreateResponse {
    private String id;
    @Schema(
            description = "Logical consent ID (remains same across versions)",
            example = "cst_123e4567-e89b-12d3-a456-426614174000"
    )
    private String consentId;
    private String secureCode;
    private String templateId;
    private Integer templateVersion;
    private String businessId;
    private String languagePreferences;
    private Multilingual multilingual;
    private List<String> preferences;
    private CustomerIdentifiers customerIdentifiers;
    private LocalDateTime startDate;
    private LocalDateTime endDate;
    private Status status;
    @Schema(
            description = "JWT token for this consent - use for validation",
            example = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJjb25zZW50SWQiOiJjc3RfMTIzZTQ1NjciLCJleHAiOjE3MzAwMDAwMDB9.abcd1234"
    )
    private String consentJwtToken;

    @Schema(
            description = "Timestamp when consent was created",
            example = "2025-09-29T10:30:00.123Z"
    )
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private String className;
    @Schema(
            description = "Success message",
            example = "Consent created successfully!"
    )
    private String message;
    @Schema(
            description = "Expiry date and time of this consent",
            example = "2026-09-29T10:30:00"
    )
    private LocalDateTime consentExpiry;
}
