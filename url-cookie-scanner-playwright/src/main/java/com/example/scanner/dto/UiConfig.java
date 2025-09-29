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
public class UiConfig {

    @Schema(description = "Logo in Base64", example = "Base64ofPNG")
    @NotBlank(message = "Logo is required and cannot be empty")
    private String logo;

    @Schema(description = "Theme", example = "theme-data")
    @NotBlank(message = "Theme is required and cannot be empty")
    private String theme;

    @Schema(description = "Dark mode enabled", example = "false")
    private boolean darkMode;

    @Schema(description = "Mobile view enabled", example = "true")
    private boolean mobileView;

    @Schema(description = "Parental control enabled", example = "false")
    private boolean parentalControl;

    @Schema(description = "Show data type", example = "true")
    private boolean dataTypeToBeShown;

    @Schema(description = "Show data item", example = "true")
    private boolean dataItemToBeShown;

    @Schema(description = "Show process activity name", example = "true")
    private boolean processActivityNameToBeShown;

    @Schema(description = "Show processor name", example = "true")
    private boolean processorNameToBeShown;

    @Schema(description = "Show validity", example = "true")
    private boolean validitytoBeShown;
}