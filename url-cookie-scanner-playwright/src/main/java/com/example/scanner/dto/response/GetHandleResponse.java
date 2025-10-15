package com.example.scanner.dto.response;

import com.example.scanner.dto.CustomerIdentifiers;
import com.example.scanner.dto.Multilingual;
import com.example.scanner.enums.ConsentHandleStatus;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
@AllArgsConstructor
@NoArgsConstructor
@Schema(description = "Consent handle details with associated template information")
public class GetHandleResponse {
    @Schema(
            description = "Consent handle ID",
            example = "handle_123e4567-e89b-12d3-a456-426614174000"
    )
    private String consentHandleId;

    @Schema(
            description = "Template ID associated with this handle",
            example = "tpl_123e4567-e89b-12d3-a456-426614174000"
    )
    private String templateId;

    @Schema(
            description = "Template name",
            example = "Main Website Cookie Template"
    )
    private String templateName;

    @Schema(
            description = "Template version number",
            example = "1"
    )
    private int templateVersion;

    @Schema(
            description = "Business ID",
            example = "0c092ed7-e99d-4ef7-8b1f-a3898e788832"
    )
    private String businessId;

    @Schema(
            description = "Multilingual content configuration",
            implementation = Multilingual.class
    )
    private Multilingual multilingual;

    @Schema(
            description = "List of preference configurations with associated cookies",
            implementation = PreferenceWithCookies.class
    )
    private List<PreferenceWithCookies> preferences;

    @Schema(
            description = "Customer identification details",
            implementation = CustomerIdentifiers.class
    )
    private CustomerIdentifiers customerIdentifiers;

    @Schema(
            description = "Current status of the handle",
            example = "ACTIVE"
    )
    private ConsentHandleStatus status;
}