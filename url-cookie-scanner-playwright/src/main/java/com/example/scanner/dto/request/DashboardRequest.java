package com.example.scanner.dto.request;

import com.example.scanner.enums.Status;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Schema(description = "Dashboard API request for fetching consent data")
public class DashboardRequest {

    @NotBlank(message = "Template ID is mandatory")
    @Schema(description = "Template ID (mandatory)", example = "template-123", required = true)
    private String templateID;

    @Schema(description = "Scan ID (optional)", example = "scan-456")
    private String scanID;

    @Schema(description = "Template version (optional)", example = "2")
    private Integer version;

    @Schema(description = "Start date filter (optional)", example = "2025-01-01T00:00:00")
    private LocalDateTime startDate;

    @Schema(description = "End date filter (optional)", example = "2025-12-31T23:59:59")
    private LocalDateTime endDate;

    @Schema(description = "Consent status (optional)", example = "ACTIVE")
    private Status status;
}