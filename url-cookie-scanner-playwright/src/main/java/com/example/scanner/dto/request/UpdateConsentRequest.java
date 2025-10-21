package com.example.scanner.dto.request;

import com.example.scanner.enums.PreferenceStatus;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import jakarta.validation.constraints.NotNull;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
@Schema(description = "Request to update an existing consent (creates new version)")
public class UpdateConsentRequest {

    @Schema(description = "Consent handle ID for this update operation",
            example = "handle_123e4567-e89b-12d3-a456-426614174000", required = true)
    @NotNull(message = "Consent handle ID is required for updates")
    private String consentHandleId;

    @Schema(
            description = "Updated language preference - must be one of LANGUAGE enum values",
            example = "HINDI",
            allowableValues = {"ASSAMESE", "BENGALI", "BODO", "DOGRI", "GUJARATI", "HINDI", "KANNADA", "KASHMIRI", "KONKANI", "MAITHILI", "MALAYALAM", "MANIPURI", "MARATHI", "NEPALI", "ODIA", "PUNJABI", "SANSKRIT", "SANTALI", "SINDHI", "TAMIL", "TELUGU", "URDU", "ENGLISH"}
    )
    private String languagePreference;

    @Schema(
            description = "Map of category names",
            example = "{\"Necessary\": \"ACCEPTED\", \"Analytics\": \"ACCEPTED\"...}"
    )
    private Map<String, PreferenceStatus> preferencesStatus;

    @Schema(
            description = "Template version to reference (optional - uses latest if not provided)",
            example = "2"
    )
    private Integer templateVersion;
    /**
     * Validation method to ensure at least one field is provided for update
     */
    public boolean hasUpdates() {
        return languagePreference != null ||
                (preferencesStatus != null && !preferencesStatus.isEmpty()) ||
                templateVersion != null;
    }

    /**
     * Check if this update contains preference changes
     */
    public boolean hasPreferenceUpdates() {
        return preferencesStatus != null && !preferencesStatus.isEmpty();
    }
}