package com.example.scanner.dto.request;

import com.example.scanner.dto.DocumentMeta;
import com.example.scanner.dto.Multilingual;
import com.example.scanner.dto.Preference;
import com.example.scanner.dto.UiConfig;
import com.example.scanner.enums.TemplateStatus;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class CreateTemplateRequest {

    @Schema(description = "Scan ID from completed cookie scan", example = "550e8400-e29b-41d4-a716-446655440000", required = true)
    @NotBlank(message = "Scan ID is required and must be from a completed scan")
    private String scanId;

    @Schema(description = "Name of the template", example = "Template1")
    @NotBlank(message = "Template name is required and cannot be empty")
    private String templateName;

    @Schema(description = "Business ID", example = "0c092ed7-e99d-4ef7-8b1f-a3898e788832")
    @NotBlank(message = "Business ID is required and cannot be empty")
    private String businessId;

    @Schema(description = "List of preferences")
    @NotEmpty(message = "At least one preference is required")
    @Valid
    private List<Preference> preferences;

    @Schema(description = "Multilingual content")
    @NotNull(message = "Multilingual configuration is required")
    @Valid
    private Multilingual multilingual;

    @Schema(description = "UI configuration")
    @NotNull(message = "UI configuration is required")
    @Valid
    private UiConfig uiConfig;

    @Schema(description = "Privacy policy document (Base64)")
    private String privacyPolicyDocument;

    @Schema(description = "Privacy policy document metadata")
    private DocumentMeta privacyPolicyDocumentMeta;

    @Schema(description = "Status of the template", example = "PUBLISHED")
    private TemplateStatus status;
}