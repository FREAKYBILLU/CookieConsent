package com.example.scanner.dto.request;

import com.example.scanner.dto.CustomerIdentifiers;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class CreateHandleRequest {
    @Schema(
            description = "Logical template ID",
            example = "tpl_123e4567-e89b-12d3-a456-426614174000",
            required = true
    )
    @NotBlank(message = "Template ID is required")
    private String templateId;

    @Schema(
            description = "Version number of the template",
            example = "1",
            required = true
    )
    @Positive(message = "Template version must be positive")
    private int templateVersion;

    @Schema(
            description = "Customer identification information",
            required = true,
            implementation = CustomerIdentifiers.class
    )
    @NotNull(message = "Customer identifiers are required")
    @Valid
    private CustomerIdentifiers customerIdentifiers;
}