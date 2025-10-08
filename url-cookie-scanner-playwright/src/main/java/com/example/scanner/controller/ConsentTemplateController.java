package com.example.scanner.controller;

import com.example.scanner.constants.ErrorCodes;
import com.example.scanner.dto.request.CreateTemplateRequest;
import com.example.scanner.dto.request.UpdateTemplateRequest;
import com.example.scanner.dto.response.ErrorResponse;
import com.example.scanner.dto.response.TemplateResponse;
import com.example.scanner.dto.response.UpdateTemplateResponse;
import com.example.scanner.entity.ConsentTemplate;
import com.example.scanner.service.ConsentTemplateService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
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
            description = "Creates a consent template linked to a completed cookie scan. The scanId must exist in scan_results table with COMPLETED status.",
            responses = {
                    @ApiResponse(
                            responseCode = "201",
                            description = "Template created successfully",
                            content = @Content(schema = @Schema(implementation = TemplateResponse.class))
                    ),
                    @ApiResponse(
                            responseCode = "400",
                            description = "Validation failed, missing tenant header, or scan not completed",
                            content = @Content(schema = @Schema(implementation = ErrorResponse.class))
                    ),
                    @ApiResponse(
                            responseCode = "404",
                            description = "Scan ID not found",
                            content = @Content(schema = @Schema(implementation = ErrorResponse.class))
                    ),
                    @ApiResponse(
                            responseCode = "500",
                            description = "Internal server error",
                            content = @Content(schema = @Schema(implementation = ErrorResponse.class))
                    )
            }
    )
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

            TemplateResponse response = new TemplateResponse(createdTemplate.getTemplateId(),
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
            description = "Retrieves consent templates from tenant-specific database.",
            responses = {
                    @ApiResponse(
                            responseCode = "200",
                            description = "Templates retrieved successfully",
                            content = @Content(schema = @Schema(implementation = ConsentTemplate.class))
                    ),
                    @ApiResponse(
                            responseCode = "400",
                            description = "Invalid tenant ID or missing header",
                            content = @Content(schema = @Schema(implementation = ErrorResponse.class))
                    ),
                    @ApiResponse(
                            responseCode = "404",
                            description = "No templates found",
                            content = @Content(schema = @Schema(implementation = ErrorResponse.class))
                    ),
                    @ApiResponse(
                            responseCode = "500",
                            description = "Internal server error",
                            content = @Content(schema = @Schema(implementation = ErrorResponse.class))
                    )
            }
    )
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

    @PutMapping("/{templateId}/update")
    @Operation(
            summary = "Update a template (creates new version)",
            description = "Creates a new version of an existing template. The templateId remains the same, " +
                    "but a new document with incremented version number is created. Previous version is marked as 'UPDATED'.",
            parameters = {
                    @Parameter(name = "X-Tenant-ID", description = "Tenant ID", required = true,
                            example = "tenant_123e4567-e89b-12d3-a456-426614174000"),
                    @Parameter(name = "templateId", description = "Logical Template ID (not document ID)", required = true,
                            example = "tpl_123e4567-e89b-12d3-a456-426614174000")
            },
            responses = {
                    @ApiResponse(
                            responseCode = "200",
                            description = "Template updated successfully (new version created)",
                            content = @Content(schema = @Schema(implementation = UpdateTemplateResponse.class))
                    ),
                    @ApiResponse(
                            responseCode = "400",
                            description = "Invalid request or template not found",
                            content = @Content(schema = @Schema(implementation = ErrorResponse.class))
                    ),
                    @ApiResponse(
                            responseCode = "422",
                            description = "Template cannot be updated (e.g., not published)",
                            content = @Content(schema = @Schema(implementation = ErrorResponse.class))
                    ),
                    @ApiResponse(
                            responseCode = "500",
                            description = "Internal server error",
                            content = @Content(schema = @Schema(implementation = ErrorResponse.class))
                    )
            }
    )
    public ResponseEntity<?> updateTemplate(
            @RequestHeader("X-Tenant-ID") String tenantId,
            @PathVariable String templateId,
            @org.springframework.web.bind.annotation.RequestBody @Valid UpdateTemplateRequest updateRequest) {

        try {
            log.info("Received template update request for templateId: {} in tenant: {}", templateId, tenantId);

            UpdateTemplateResponse response = service.updateTemplate(tenantId, templateId, updateRequest);

            log.info("Successfully updated template: {} to version: {}", templateId, response.getNewVersion());
            return ResponseEntity.ok(response);

        } catch (IllegalArgumentException e) {
            log.warn("Validation error for template update: {}", e.getMessage());
            return buildErrorResponse(HttpStatus.BAD_REQUEST,
                    ErrorCodes.VALIDATION_ERROR,
                    "Validation failed",
                    e.getMessage(),
                    "/cookie-templates/" + templateId + "/update");

        } catch (IllegalStateException e) {
            log.warn("Business rule violation for template update: {}", e.getMessage());
            return buildErrorResponse(HttpStatus.UNPROCESSABLE_ENTITY,
                    ErrorCodes.BUSINESS_RULE_VIOLATION,
                    "Template cannot be updated",
                    e.getMessage(),
                    "/cookie-templates/" + templateId + "/update");

        } catch (Exception e) {
            log.error("Unexpected error updating template: {} in tenant: {}", templateId, tenantId, e);
            return buildErrorResponse(HttpStatus.INTERNAL_SERVER_ERROR,
                    ErrorCodes.INTERNAL_ERROR,
                    "Failed to update template",
                    "Template update failed: " + e.getMessage(),
                    "/cookie-templates/" + templateId + "/update");
        }
    }

    @GetMapping("/{templateId}/history")
    @Operation(
            summary = "Get template version history",
            description = "Retrieves all versions of a template ordered by version number (latest first). " +
                    "Shows complete audit trail of template changes.",
            parameters = {
                    @Parameter(name = "X-Tenant-ID", description = "Tenant ID", required = true),
                    @Parameter(name = "templateId", description = "Logical Template ID", required = true)
            },
            responses = {
                    @ApiResponse(
                            responseCode = "200",
                            description = "Template history retrieved successfully",
                            content = @Content(schema = @Schema(implementation = ConsentTemplate.class))
                    ),
                    @ApiResponse(
                            responseCode = "400",
                            description = "Invalid template ID",
                            content = @Content(schema = @Schema(implementation = ErrorResponse.class))
                    ),
                    @ApiResponse(
                            responseCode = "404",
                            description = "Template not found",
                            content = @Content(schema = @Schema(implementation = ErrorResponse.class))
                    ),
                    @ApiResponse(
                            responseCode = "500",
                            description = "Internal server error",
                            content = @Content(schema = @Schema(implementation = ErrorResponse.class))
                    )
            }
    )
    public ResponseEntity<?> getTemplateHistory(
            @RequestHeader("X-Tenant-ID") String tenantId,
            @PathVariable String templateId) {

        try {
            log.info("Retrieving template history for templateId: {} in tenant: {}", templateId, tenantId);

            List<ConsentTemplate> history = service.getTemplateHistory(tenantId, templateId);

            log.info("Retrieved {} versions for template: {}", history.size(), templateId);
            return ResponseEntity.ok(history);

        } catch (IllegalArgumentException e) {
            log.warn("Template not found for history request: {}", e.getMessage());
            return buildErrorResponse(HttpStatus.NOT_FOUND,
                    ErrorCodes.NOT_FOUND,
                    "Template not found",
                    e.getMessage(),
                    "/cookie-templates/" + templateId + "/history");

        } catch (Exception e) {
            log.error("Error retrieving template history for templateId: {} in tenant: {}", templateId, tenantId, e);
            return buildErrorResponse(HttpStatus.INTERNAL_SERVER_ERROR,
                    ErrorCodes.INTERNAL_ERROR,
                    "Failed to retrieve template history",
                    "Could not retrieve template history: " + e.getMessage(),
                    "/cookie-templates/" + templateId + "/history");
        }
    }

    @GetMapping("/{templateId}/versions/{version}")
    @Operation(
            summary = "Get specific template version",
            description = "Retrieves a specific version of a template. Useful for viewing historical template configurations.",
            parameters = {
                    @Parameter(name = "X-Tenant-ID", description = "Tenant ID", required = true),
                    @Parameter(name = "templateId", description = "Logical Template ID", required = true),
                    @Parameter(name = "version", description = "Version number", required = true, example = "2")
            },
            responses = {
                    @ApiResponse(
                            responseCode = "200",
                            description = "Template version retrieved successfully",
                            content = @Content(schema = @Schema(implementation = ConsentTemplate.class))
                    ),
                    @ApiResponse(
                            responseCode = "400",
                            description = "Invalid template ID or version",
                            content = @Content(schema = @Schema(implementation = ErrorResponse.class))
                    ),
                    @ApiResponse(
                            responseCode = "404",
                            description = "Template version not found",
                            content = @Content(schema = @Schema(implementation = ErrorResponse.class))
                    ),
                    @ApiResponse(
                            responseCode = "500",
                            description = "Internal server error",
                            content = @Content(schema = @Schema(implementation = ErrorResponse.class))
                    )
            }
    )
    public ResponseEntity<?> getTemplateVersion(
            @RequestHeader("X-Tenant-ID") String tenantId,
            @PathVariable String templateId,
            @PathVariable Integer version) {

        try {
            log.info("Retrieving template version {} for templateId: {} in tenant: {}", version, templateId, tenantId);

            Optional<ConsentTemplate> templateOpt = service.getTemplateByIdAndVersion(tenantId, templateId, version);

            if (templateOpt.isEmpty()) {
                log.warn("Template version not found: templateId={}, version={}, tenant={}", templateId, version, tenantId);
                return buildErrorResponse(HttpStatus.NOT_FOUND,
                        ErrorCodes.NOT_FOUND,
                        "Template version not found",
                        "No template found with ID '" + templateId + "' and version " + version,
                        "/cookie-templates/" + templateId + "/versions/" + version);
            }

            log.info("Retrieved template version {} for templateId: {}", version, templateId);
            return ResponseEntity.ok(templateOpt.get());

        } catch (IllegalArgumentException e) {
            log.warn("Validation error for template version request: {}", e.getMessage());
            return buildErrorResponse(HttpStatus.BAD_REQUEST,
                    ErrorCodes.VALIDATION_ERROR,
                    "Invalid request parameters",
                    e.getMessage(),
                    "/cookie-templates/" + templateId + "/versions/" + version);

        } catch (Exception e) {
            log.error("Error retrieving template version for templateId: {}, version: {}, tenant: {}",
                    templateId, version, tenantId, e);
            return buildErrorResponse(HttpStatus.INTERNAL_SERVER_ERROR,
                    ErrorCodes.INTERNAL_ERROR,
                    "Failed to retrieve template version",
                    "Could not retrieve template version: " + e.getMessage(),
                    "/cookie-templates/" + templateId + "/versions/" + version);
        }
    }
}