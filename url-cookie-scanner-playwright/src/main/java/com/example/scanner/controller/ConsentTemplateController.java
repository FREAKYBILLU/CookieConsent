package com.example.scanner.controller;

import com.example.scanner.constants.ErrorCodes;
import com.example.scanner.dto.CreateTemplateRequest;
import com.example.scanner.dto.ErrorResponse;
import com.example.scanner.dto.TemplateResponse;
import com.example.scanner.entity.ConsentTemplate;
import com.example.scanner.service.ConsentTemplateService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.BindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/cookie-templates")
@Tag(name = "Consent Template", description = "APIs for managing validated consent templates linked to completed scans")
@Slf4j
public class ConsentTemplateController {

    @Autowired
    private ConsentTemplateService service;

    @Operation(
            summary = "Create consent template for completed scan",
            description = "Creates a consent template linked to a completed cookie scan. The scanId must exist in scan_results table with COMPLETED status."
    )
    @ApiResponse(responseCode = "201", description = "Template created successfully")
    @ApiResponse(responseCode = "400", description = "Validation failed, missing tenant header, or scan not completed")
    @ApiResponse(responseCode = "404", description = "Scan ID not found")
    @ApiResponse(responseCode = "500", description = "Internal server error")
    @PostMapping
    public ResponseEntity<?> createTemplate(
            @Parameter(description = "Tenant ID for multi-tenant database routing", required = true)
            @RequestHeader("X-Tenant-ID") String tenantId,
            @Valid @RequestBody CreateTemplateRequest createRequest,
            BindingResult bindingResult) {

        try {
            // Validate tenant ID from header
            if (tenantId == null || tenantId.trim().isEmpty()) {
                return buildErrorResponse(HttpStatus.BAD_REQUEST,
                        ErrorCodes.VALIDATION_ERROR,
                        "Tenant ID is required",
                        "X-Tenant-ID header is missing or empty",
                        "/cookie-templates");
            }

            // Check for validation errors
            if (bindingResult.hasErrors()) {
                return handleValidationErrors(bindingResult, "/cookie-templates");
            }

            log.info("Creating template '{}' for tenant: {} linked to scan: {}",
                    createRequest.getTemplateName(), tenantId, createRequest.getScanId());

            ConsentTemplate createdTemplate = service.createTemplate(tenantId, createRequest);

            TemplateResponse response = new TemplateResponse(createdTemplate.getId(),
                    "Template created successfully and linked to scan: " + createdTemplate.getScanId());

            log.info("Successfully created template with ID: {} linked to scan: {}",
                    createdTemplate.getId(), createdTemplate.getScanId());

            return new ResponseEntity<>(response, HttpStatus.CREATED);

        } catch (IllegalArgumentException e) {
            log.warn("Validation failed for template creation: {}", e.getMessage());
            return buildErrorResponse(HttpStatus.BAD_REQUEST,
                    ErrorCodes.VALIDATION_ERROR,
                    "Validation failed",
                    e.getMessage(),
                    "/cookie-templates");

        } catch (RuntimeException e) {
            if (e.getMessage().contains("not found") || e.getMessage().contains("does not exist")) {
                log.warn("Scan not found for template creation: {}", e.getMessage());
                return buildErrorResponse(HttpStatus.NOT_FOUND,
                        ErrorCodes.NOT_FOUND,
                        "Scan not found",
                        e.getMessage(),
                        "/cookie-templates");
            } else {
                log.error("Unexpected error creating template for tenant: {}", tenantId, e);
                return buildErrorResponse(HttpStatus.INTERNAL_SERVER_ERROR,
                        ErrorCodes.INTERNAL_ERROR,
                        "Failed to create consent template",
                        "Template creation failed: " + e.getMessage(),
                        "/cookie-templates");
            }
        } catch (Exception e) {
            log.error("Unexpected error creating template for tenant: {}", tenantId, e);
            return buildErrorResponse(HttpStatus.INTERNAL_SERVER_ERROR,
                    ErrorCodes.INTERNAL_ERROR,
                    "Failed to create consent template",
                    "Template creation failed: " + e.getMessage(),
                    "/cookie-templates");
        }
    }

    @Operation(
            summary = "Get templates by tenant ID with optional scan ID filter",
            description = "Retrieves consent templates from tenant-specific database."
    )
    @ApiResponse(responseCode = "200", description = "Templates retrieved successfully")
    @ApiResponse(responseCode = "400", description = "Invalid tenant ID or missing header")
    @ApiResponse(responseCode = "404", description = "No templates found")
    @GetMapping("/tenant")
    public ResponseEntity<?> getTemplatesByTenantAndScanId(
            @Parameter(description = "Tenant ID for multi-tenant database routing", required = true)
            @RequestHeader("X-Tenant-ID") String tenantId,
            @Parameter(description = "Scan ID (optional) - if provided, filters to specific template")
            @RequestParam(value = "scanId", required = false) String scanId) {

        try {
            // Validate tenant ID from header
            if (tenantId == null || tenantId.trim().isEmpty()) {
                return buildErrorResponse(HttpStatus.BAD_REQUEST,
                        ErrorCodes.VALIDATION_ERROR,
                        "Tenant ID is required",
                        "X-Tenant-ID header is missing or empty",
                        "/cookie-templates/tenant");
            }

            if (scanId != null && !scanId.trim().isEmpty()) {
                Optional<ConsentTemplate> template = service.getTemplateByTenantAndScanId(tenantId, scanId);
                if (template.isPresent()) {
                    return ResponseEntity.ok(template.get());
                } else {
                    return buildErrorResponse(HttpStatus.NOT_FOUND,
                            ErrorCodes.NOT_FOUND,
                            "Consent template not found",
                            "No template found with scanId: " + scanId + " for tenantId: " + tenantId,
                            "/cookie-templates/tenant");
                }
            } else {
                List<ConsentTemplate> templates = service.getTemplatesByTenantId(tenantId);
                return ResponseEntity.ok(templates);
            }

        } catch (Exception e) {
            log.error("Unexpected error retrieving templates for tenant: {}", tenantId, e);
            return buildErrorResponse(HttpStatus.INTERNAL_SERVER_ERROR,
                    ErrorCodes.INTERNAL_ERROR,
                    "Failed to retrieve templates",
                    "Internal server error: " + e.getMessage(),
                    "/cookie-templates/tenant");
        }
    }

    private ResponseEntity<ErrorResponse> handleValidationErrors(BindingResult bindingResult, String path) {
        List<String> errors = bindingResult.getAllErrors().stream()
                .map(error -> {
                    if (error instanceof FieldError) {
                        FieldError fieldError = (FieldError) error;
                        return fieldError.getField() + ": " + error.getDefaultMessage();
                    } else {
                        return error.getDefaultMessage();
                    }
                })
                .collect(Collectors.toList());

        String errorMessage = "Validation failed: " + String.join(", ", errors);

        return buildErrorResponse(HttpStatus.BAD_REQUEST,
                ErrorCodes.VALIDATION_ERROR,
                "Request validation failed",
                errorMessage,
                path);
    }

    private ResponseEntity<ErrorResponse> buildErrorResponse(HttpStatus status, String errorCode,
                                                             String message, String details, String path) {
        ErrorResponse errorResponse = new ErrorResponse(
                errorCode,
                message,
                details,
                Instant.now(),
                path
        );
        return ResponseEntity.status(status).body(errorResponse);
    }
}