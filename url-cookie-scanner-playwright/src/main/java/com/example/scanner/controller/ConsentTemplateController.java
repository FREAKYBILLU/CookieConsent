package com.example.scanner.controller;

import com.example.scanner.constants.ErrorCodes;
import com.example.scanner.dto.ErrorResponse;
import com.example.scanner.dto.TemplateResponse;
import com.example.scanner.entity.ConsentTemplate;
import com.example.scanner.service.ConsentTemplateService;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/cookie-templates")
@Tag(name = "Consent Template", description = "APIs for managing consent templates with scan ID")
public class ConsentTemplateController {

    @Autowired
    private ConsentTemplateService service;

    @Operation(
            summary = "Create new consent template for tenant",
            description = "Creates a new consent template with auto-generated scan ID in tenant-specific database"
    )
    @ApiResponse(responseCode = "201", description = "Template created successfully")
    @ApiResponse(responseCode = "400", description = "Invalid request data or missing tenant header")
    @PostMapping
    public ResponseEntity<?> createTemplate(
            @Parameter(description = "Tenant ID for multi-tenant database routing", required = true)
            @RequestHeader("X-Tenant-ID") String tenantId,
            @Valid @RequestBody ConsentTemplate template) {
        try {
            ConsentTemplate createdTemplate = service.createTemplate(tenantId, template);
            if(createdTemplate != null) {
                TemplateResponse response = new TemplateResponse(createdTemplate.getId(),
                        "Template created successfully"
                );
                return new ResponseEntity<>(response, HttpStatus.CREATED);
            }else{
                ErrorResponse errorResponse = new ErrorResponse(
                        ErrorCodes.VALIDATION_ERROR,
                        "Failed to create consent template",
                        "Template creation failed due to some unexpected validation error ",
                        Instant.now(),
                        "/cookie-consent-templates"
                );
                return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(errorResponse);
            }
        } catch (Exception e) {
            ErrorResponse errorResponse = new ErrorResponse(
                    ErrorCodes.VALIDATION_ERROR,
                    "Failed to create consent template",
                    "Template creation failed: " + e.getMessage(),
                    Instant.now(),
                    "/api/v1/consent-templates"
            );
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(errorResponse);
        }
    }

    @Operation(
            summary = "Get templates by tenant ID with optional scan ID filter",
            description = "Retrieves consent templates from tenant-specific database. If scan ID is provided, returns specific template for that tenant and scan ID combination."
    )
    @ApiResponse(responseCode = "200", description = "Templates retrieved successfully")
    @ApiResponse(responseCode = "400", description = "Invalid tenant ID or missing header")
    @ApiResponse(responseCode = "404", description = "No templates found")
    @ApiResponse(responseCode = "500", description = "Internal server error")
    @GetMapping("/tenant")
    public ResponseEntity<?> getTemplatesByTenantAndScanId(
            @Parameter(description = "Tenant ID for multi-tenant database routing", required = true)
            @RequestHeader("X-Tenant-ID") String tenantId,
            @Parameter(description = "Scan ID (optional) - if provided, filters to specific template")
            @RequestParam(value = "scanId", required = false) String scanId) {

        try {
            // Validate tenant ID from header
            if (tenantId == null || tenantId.trim().isEmpty()) {
                ErrorResponse errorResponse = new ErrorResponse(
                        ErrorCodes.VALIDATION_ERROR,
                        "Tenant ID is required",
                        "X-Tenant-ID header is missing or empty",
                        Instant.now(),
                        "/api/v1/consent-templates/tenant"
                );
                return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(errorResponse);
            }

            if (scanId != null && !scanId.trim().isEmpty()) {
                Optional<ConsentTemplate> template = service.getTemplateByTenantAndScanId(tenantId, scanId);
                if (template.isPresent()) {
                    return ResponseEntity.ok(template.get());
                } else {
                    ErrorResponse errorResponse = new ErrorResponse(
                            ErrorCodes.NOT_FOUND,
                            "Consent template not found",
                            "No template found with scanId: " + scanId + " for tenantId: " + tenantId,
                            Instant.now(),
                            "/api/v1/consent-templates/tenant"
                    );
                    return ResponseEntity.status(HttpStatus.NOT_FOUND).body(errorResponse);
                }
            } else {
                // Only tenantId provided - return all templates for this tenant from tenant DB
                List<ConsentTemplate> templates = service.getTemplatesByTenantId(tenantId);
                return ResponseEntity.ok(templates);
            }

        } catch (IllegalArgumentException e) {
            ErrorResponse errorResponse = new ErrorResponse(
                    ErrorCodes.VALIDATION_ERROR,
                    "Invalid input provided",
                    "Invalid input: " + e.getMessage(),
                    Instant.now(),
                    "/api/v1/consent-templates/tenant"
            );
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(errorResponse);

        } catch (Exception e) {
            ErrorResponse errorResponse = new ErrorResponse(
                    ErrorCodes.INTERNAL_ERROR,
                    "Failed to retrieve templates",
                    "Internal server error: " + e.getMessage(),
                    Instant.now(),
                    "/api/v1/consent-templates/tenant"
            );
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(errorResponse);
        }
    }
}