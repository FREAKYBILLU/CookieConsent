package com.example.scanner.controller;

import com.example.scanner.constants.ErrorCodes;
import com.example.scanner.dto.request.DashboardRequest;
import com.example.scanner.dto.response.DashboardTemplateResponse;
import com.example.scanner.dto.response.ErrorResponse;
import com.example.scanner.service.ConsentService;
import com.example.scanner.util.CommonUtil;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;

@RestController
@RequestMapping("/dashboard")
@Tag(name = "Dashboard", description = "APIs for consent dashboard analytics and reporting")
@Slf4j
public class DashboardController {

    @Autowired
    private ConsentService consentService;

    @Operation(
            summary = "Get consent dashboard data",
            description = "Fetches consent data grouped by template. TenantId is mandatory as path parameter. " +
                    "All query parameters are optional. Returns all templates if only tenantId is provided.",
            responses = {
                    @ApiResponse(
                            responseCode = "200",
                            description = "Dashboard data retrieved successfully",
                            content = @Content(
                                    mediaType = "application/json",
                                    array = @ArraySchema(
                                            schema = @Schema(implementation = DashboardTemplateResponse.class)
                                    )
                            )
                    ),
                    @ApiResponse(
                            responseCode = "404",
                            description = "No data found",
                            content = @Content(schema = @Schema(implementation = ErrorResponse.class))
                    ),
                    @ApiResponse(
                            responseCode = "500",
                            description = "Internal server error",
                            content = @Content(schema = @Schema(implementation = ErrorResponse.class))
                    )
            }
    )
    @GetMapping("/{tenantId}")
    public ResponseEntity<?> getDashboardData(
            @Parameter(description = "Tenant ID (mandatory)", required = true)
            @PathVariable("tenantId") String tenantId,

            @Parameter(description = "Template ID (optional)")
            @RequestParam(value = "template", required = false) String templateId,

            @Parameter(description = "Scan ID (optional)")
            @RequestParam(value = "scanId", required = false) String scanId,

            @Parameter(description = "Template version (optional)")
            @RequestParam(value = "version", required = false) Integer version,

            @Parameter(description = "Start date filter (optional)")
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
            @RequestParam(value = "startDt", required = false) LocalDateTime startDate,

            @Parameter(description = "End date filter (optional)")
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
            @RequestParam(value = "endDt", required = false) LocalDateTime endDate,

            HttpServletRequest httpRequest) {

        try {
            // Validation: Tenant ID
            CommonUtil.validateTenantId(tenantId);

            // Create request object for service layer
            DashboardRequest request = DashboardRequest.builder()
                    .templateID(templateId)
                    .scanID(scanId)
                    .version(version)
                    .startDate(startDate)
                    .endDate(endDate)
                    .build();

            // Fetch dashboard data
            List<DashboardTemplateResponse> dashboardData =
                    consentService.getDashboardDataGroupedByTemplate(tenantId, request);

            if (dashboardData.isEmpty()) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND).body(
                        new ErrorResponse(
                                "NO_DATA_FOUND",
                                "No data found",
                                "No templates or consents found for the given criteria",
                                Instant.now(),
                                httpRequest.getRequestURI()
                        )
                );
            }

            return ResponseEntity.ok(dashboardData);

        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(
                    new ErrorResponse(
                            ErrorCodes.INTERNAL_ERROR,
                            "Internal server error",
                            e.getMessage(),
                            Instant.now(),
                            httpRequest.getRequestURI()
                    )
            );
        }
    }
}