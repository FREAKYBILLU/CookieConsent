package com.example.scanner.controller;


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

import java.util.List;
import java.util.Optional;

@RestController
@RequestMapping("/api/v1/consent-templates")
@Tag(name = "Consent Template", description = "APIs for managing consent templates with scan ID")
public class ConsentTemplateController {

    @Autowired
    private ConsentTemplateService service;

    @Operation(
            summary = "Create new consent template",
            description = "Creates a new consent template with auto-generated scan ID"
    )
    @ApiResponse(responseCode = "201", description = "Template created successfully")
    @ApiResponse(responseCode = "400", description = "Invalid request data")
    @PostMapping
    public ResponseEntity<ConsentTemplate> createTemplate(@Valid @RequestBody ConsentTemplate template) {
        try {
            ConsentTemplate createdTemplate = service.createTemplate(template);
            return new ResponseEntity<>(createdTemplate, HttpStatus.CREATED);
        } catch (Exception e) {
            return new ResponseEntity<>(HttpStatus.BAD_REQUEST);
        }
    }

    @Operation(
            summary = "Get template by scan ID",
            description = "Retrieves consent template details using scan ID"
    )
    @ApiResponse(responseCode = "200", description = "Template found")
    @ApiResponse(responseCode = "404", description = "Template not found")
    @GetMapping("/{scanId}")
    public ResponseEntity<ConsentTemplate> getTemplateByScanId(
            @Parameter(description = "Scan ID of the template") @PathVariable String scanId) {
        Optional<ConsentTemplate> template = service.getTemplateByScanId(scanId);
        return template.map(t -> ResponseEntity.ok(t))
                .orElse(ResponseEntity.notFound().build());
    }

    @Operation(
            summary = "Get templates by business ID",
            description = "Retrieves all templates for a specific business"
    )
    @ApiResponse(responseCode = "200", description = "Templates retrieved successfully")
    @GetMapping("/business/{businessId}")
    public ResponseEntity<List<ConsentTemplate>> getTemplatesByBusinessId(
            @Parameter(description = "Business ID") @PathVariable String businessId) {
        List<ConsentTemplate> templates = service.getTemplatesByBusinessId(businessId);
        return ResponseEntity.ok(templates);
    }

    @Operation(
            summary = "Get templates by status",
            description = "Retrieves all templates with specific status (DRAFT, PUBLISHED, ARCHIVED)"
    )
    @ApiResponse(responseCode = "200", description = "Templates retrieved successfully")
    @GetMapping("/status/{status}")
    public ResponseEntity<List<ConsentTemplate>> getTemplatesByStatus(
            @Parameter(description = "Template status") @PathVariable String status) {
        List<ConsentTemplate> templates = service.getTemplatesByStatus(status);
        return ResponseEntity.ok(templates);
    }

    @Operation(
            summary = "Update template",
            description = "Updates an existing consent template using scan ID"
    )
    @ApiResponse(responseCode = "200", description = "Template updated successfully")
    @ApiResponse(responseCode = "404", description = "Template not found")
    @PutMapping("/{scanId}")
    public ResponseEntity<ConsentTemplate> updateTemplate(
            @Parameter(description = "Scan ID of the template") @PathVariable String scanId,
            @Valid @RequestBody ConsentTemplate template) {
        try {
            ConsentTemplate updatedTemplate = service.updateTemplate(scanId, template);
            return ResponseEntity.ok(updatedTemplate);
        } catch (RuntimeException e) {
            return ResponseEntity.notFound().build();
        }
    }

    @Operation(
            summary = "Delete template",
            description = "Deletes a consent template using scan ID"
    )
    @ApiResponse(responseCode = "204", description = "Template deleted successfully")
    @ApiResponse(responseCode = "404", description = "Template not found")
    @DeleteMapping("/{scanId}")
    public ResponseEntity<Void> deleteTemplate(
            @Parameter(description = "Scan ID of the template") @PathVariable String scanId) {
        boolean deleted = service.deleteTemplate(scanId);
        return deleted ? ResponseEntity.noContent().build() : ResponseEntity.notFound().build();
    }

    @Operation(
            summary = "Publish template",
            description = "Changes template status to PUBLISHED"
    )
    @ApiResponse(responseCode = "200", description = "Template published successfully")
    @ApiResponse(responseCode = "404", description = "Template not found")
    @PatchMapping("/{scanId}/publish")
    public ResponseEntity<ConsentTemplate> publishTemplate(
            @Parameter(description = "Scan ID of the template") @PathVariable String scanId) {
        try {
            ConsentTemplate publishedTemplate = service.publishTemplate(scanId);
            return ResponseEntity.ok(publishedTemplate);
        } catch (RuntimeException e) {
            return ResponseEntity.notFound().build();
        }
    }
}