package com.example.scanner.dto.request;


import com.example.scanner.enums.PreferenceStatus;
import com.example.scanner.enums.Purpose;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
@Schema(description = "Request to create a new consent based on user's cookie preferences")
public class CreateConsentRequest {

    @Schema(description = "Unique consent handle ID obtained from consent handle creation",
            example = "d7cbde9d-c46b-4e8e-8fb7-0c143d77a013",
            required = true)
    @NotBlank(message = "Consent handle ID is required")
    private String consentHandleId;

    @Schema(description = "User's preferred language for consent display. Must match one of the supported languages in the template",
            example = "ENGLISH",
            allowableValues = {"ASSAMESE", "BENGALI", "BODO", "DOGRI", "GUJARATI", "HINDI", "KANNADA", "KASHMIRI",
                    "KONKANI", "MAITHILI", "MALAYALAM", "MANIPURI", "MARATHI", "NEPALI", "ODIA",
                    "PUNJABI", "SANSKRIT", "SANTALI", "SINDHI", "TAMIL", "TELUGU", "URDU", "ENGLISH"})
    private String languagePreference;

    @Schema(
            description = "Map of purpose IDs to their acceptance status",
            example = "{\"NECESSARY\": \"ACCEPTED\", \"ANALYTICS\": \"ACCEPTED\", \"ADVERTISEMENT\": \"NOTACCEPTED\"" +
                    ", \"FUNCTIONAL\": \"NOTACCEPTED\", \"OTHERS\": \"NOTACCEPTED\"}",
            required = true
    )
    Map<Purpose, PreferenceStatus> preferencesStatus;
}
