package com.example.scanner.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
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
public class LanguageSpecificContent {

    @Schema(description = "Description", example = "While using JioMeet...")
    @NotBlank(message = "JCMP1028")
    private String description;

    @Schema(description = "Label", example = "Required")
    @NotBlank(message = "JCMP1029")
    private String label;

    @Schema(description = "Rights text", example = "To withdraw your consent...")
    @NotBlank(message = "JCMP1030")
    private String rightsText;

    @Schema(description = "Permission text", example = "By clicking 'Allow all'...")
    @NotBlank(message = "JCMP1031")
    private String permissionText;
}