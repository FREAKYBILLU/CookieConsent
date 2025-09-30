package com.example.scanner.dto;

import com.example.scanner.enums.PreferenceStatus;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
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
public class Preference {

    @Schema(hidden = true)
    private String preferenceId;

    @Schema(
            description = "Comma-separated string of purpose IDs (e.g., 'essential-cookies,functionality-cookies'). This is a single string, not an array.",
            example = "essential-cookies,functionality-cookies,analytics-cookies",
            required = true
    )
    @NotEmpty(message = "Purpose IDs are required for each preference")
    private String purposeIds;

    @Schema(
            description = "Whether this preference category is mandatory (true) or optional (false)",
            example = "false",
            required = true
    )
    private boolean isMandatory;

    @Schema(
            description = "Validity period for this preference",
            required = true,
            implementation = Duration.class
    )
    @NotNull(message = "Preference validity is required")
    @Valid
    private Duration preferenceValidity;

    @Schema(hidden = true)
    private LocalDateTime startDate;

    @Schema(hidden = true)
    private LocalDateTime endDate;

    @Schema(hidden = true)
    private PreferenceStatus preferenceStatus;
}