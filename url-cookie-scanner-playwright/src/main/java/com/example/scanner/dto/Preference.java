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

    @Schema(description = "List of purpose IDs", example = "[\"UUID_1\", \"UUID_2\"]")
    @NotEmpty(message = "JCMP1017")
    private List<String> purposeIds;

    @Schema(description = "Is this preference mandatory?", example = "false")
    private boolean isMandatory;

    @Schema(description = "Should this preference auto-renew?", example = "false")
    private boolean autoRenew;

    @Schema(description = "Validity of the preference")
    @NotNull(message = "JCMP1018")
    @Valid
    private Duration preferenceValidity;

    @Schema(hidden = true)
    private LocalDateTime startDate;

    @Schema(hidden = true)
    private LocalDateTime endDate;

    @Schema(description = "List of purpose activity IDs", example = "[\"UUID_1\", \"UUID_2\"]")
    @NotEmpty(message = "JCMP1019")
    private List<String> processorActivityIds;

    @Schema(hidden = true)
    private PreferenceStatus preferenceStatus;
}