package com.example.scanner.controller;

import com.example.scanner.constants.ErrorCodes;
import com.example.scanner.dto.request.DashboardRequest;
import com.example.scanner.dto.response.DashboardResponse;
import com.example.scanner.dto.response.ErrorResponse;
import com.example.scanner.exception.ConsentException;
import com.example.scanner.service.ConsentService;
import com.example.scanner.util.CommonUtil;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/dashboard")
@Tag(name = "Dashboard", description = "APIs for consent dashboard analytics and reporting")
@Slf4j
public class DashboardController {

    @Autowired
    private ConsentService consentService;

    @Operation(
            summary = "Get consent data for dashboard",
            description = "Fetches consent data based on mandatory templateID with optional filters. " +
                    "Supports filtering by scanID, version, date range, and consent status. " +
                    "Returns enriched data including scanned site, subdomains, and cookie categories.",
            responses = {
                    @ApiResponse(
                            responseCode = "200",
                            description = "Dashboard data retrieved successfully",
                            content = @Content(schema = @Schema(implementation = DashboardResponse.class))
                    ),
                    @ApiResponse(
                            responseCode = "400",
                            description = "Validation error",
                            content = @Content(schema = @Schema(implementation = ErrorResponse.class))
                    ),
                    @ApiResponse(
                            responseCode = "404",
                            description = "No consents found",
                            content = @Content(schema = @Schema(implementation = ErrorResponse.class))
                    ),
                    @ApiResponse(
                            responseCode = "500",
                            description = "Internal server error",
                            content = @Content(schema = @Schema(implementation = ErrorResponse.class))
                    )
            }
    )
    @PostMapping("/consents")
    public ResponseEntity<?> getDashboardData(
            @Parameter(description = "Tenant ID for multi-tenant database routing", required = true)
            @RequestHeader("X-Tenant-ID") String tenantId,
            @Valid @RequestBody DashboardRequest request,
            BindingResult bindingResult,
            HttpServletRequest httpRequest) {

        try {
            log.info("Dashboard API called - tenant: {}, templateID: {}",
                    tenantId, request != null ? request.getTemplateID() : "null");

            // Validation: Binding errors
            if (bindingResult.hasErrors()) {
                String errors = bindingResult.getFieldErrors().stream()
                        .map(error -> error.getField() + ": " + error.getDefaultMessage())
                        .collect(Collectors.joining(", "));

                log.warn("Validation failed: {}", errors);
                return ResponseEntity.badRequest().body(
                        new ErrorResponse(
                                ErrorCodes.VALIDATION_ERROR,
                                "Validation failed",
                                errors,
                                Instant.now(),
                                httpRequest.getRequestURI()
                        )
                );
            }

            // Validation: Tenant ID
            CommonUtil.validateTenantId(tenantId);

            // Validation: Template ID
            CommonUtil.validateTemplateId(request.getTemplateID());

            // Validation: Version
            CommonUtil.validateVersion(request.getVersion());

            // Validation: Date range
            CommonUtil.validateDateRange(request.getStartDate(), request.getEndDate());

            // Fetch dashboard data
            List<DashboardResponse> dashboardData = consentService.getDashboardData(tenantId, request);

            // Check if empty
            if (dashboardData.isEmpty()) {
                log.info("No consents found for templateID: {}", request.getTemplateID());
                return ResponseEntity.status(HttpStatus.NOT_FOUND).body(
                        new ErrorResponse(
                                "NO_DATA_FOUND",
                                "No consents found",
                                "No consents matching the criteria for templateID: " + request.getTemplateID(),
                                Instant.now(),
                                httpRequest.getRequestURI()
                        )
                );
            }

            log.info("Successfully retrieved {} consent records", dashboardData.size());
            return ResponseEntity.ok(dashboardData);

        } catch (IllegalArgumentException e) {
            log.error("Validation error: {}", e.getMessage());
            return ResponseEntity.badRequest().body(
                    new ErrorResponse(
                            ErrorCodes.VALIDATION_ERROR,
                            "Validation error",
                            e.getMessage(),
                            Instant.now(),
                            httpRequest.getRequestURI()
                    )
            );

        } catch (ConsentException e) {
            log.error("ConsentException: {} - {}", e.getErrorCode(), e.getMessage());
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(
                    new ErrorResponse(
                            e.getErrorCode(),
                            e.getUserMessage(),
                            e.getDeveloperDetails(),
                            Instant.now(),
                            httpRequest.getRequestURI()
                    )
            );

        } catch (Exception e) {
            log.error("Unexpected error in dashboard API", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(
                    new ErrorResponse(
                            ErrorCodes.INTERNAL_ERROR,
                            "Internal server error",
                            "Failed to fetch dashboard data: " + e.getMessage(),
                            Instant.now(),
                            httpRequest.getRequestURI()
                    )
            );
        }
    }
}