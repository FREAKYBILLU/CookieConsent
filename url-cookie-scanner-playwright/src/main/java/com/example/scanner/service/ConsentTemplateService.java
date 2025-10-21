package com.example.scanner.service;

import com.example.scanner.config.MultiTenantMongoConfig;
import com.example.scanner.config.TenantContext;
import com.example.scanner.constants.ErrorCodes;
import com.example.scanner.dto.request.CreateTemplateRequest;
import com.example.scanner.dto.Preference;
import com.example.scanner.dto.request.UpdateTemplateRequest;
import com.example.scanner.dto.response.UpdateTemplateResponse;
import com.example.scanner.entity.ConsentTemplate;
import com.example.scanner.entity.ScanResultEntity;
import com.example.scanner.enums.PreferenceStatus;
import com.example.scanner.enums.TemplateStatus;
import com.example.scanner.enums.VersionStatus;
import com.example.scanner.exception.ConsentException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.example.scanner.service.CategoryService;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class ConsentTemplateService {

    private final MultiTenantMongoConfig mongoConfig;
    private final CategoryService categoryService;

    public Optional<ConsentTemplate> getTemplateByTenantAndScanId(String tenantId, String scanId) {
        validateInputs(tenantId, "Tenant ID cannot be null or empty");
        validateInputs(scanId, "Scan ID cannot be null or empty");

        TenantContext.setCurrentTenant(tenantId);
        MongoTemplate tenantMongoTemplate = mongoConfig.getMongoTemplateForTenant(tenantId);

        try {
            Query query = new Query(Criteria.where("scanId").is(scanId).and("templateStatus").is("ACTIVE"));
            ConsentTemplate template = tenantMongoTemplate.findOne(query, ConsentTemplate.class);
            return Optional.ofNullable(template);
        } finally {
            TenantContext.clear();
        }
    }

    public Optional<ConsentTemplate> getTemplateByTenantAndTemplateId(String tenantId, String templateId) {
        validateInputs(tenantId, "Tenant ID cannot be null or empty");
        validateInputs(templateId, "template ID cannot be null or empty");

        TenantContext.setCurrentTenant(tenantId);
        MongoTemplate tenantMongoTemplate = mongoConfig.getMongoTemplateForTenant(tenantId);

        try {
            Query query = new Query(Criteria.where("templateId").is(templateId));
            ConsentTemplate template = tenantMongoTemplate.findOne(query, ConsentTemplate.class);
            return Optional.ofNullable(template);
        } finally {
            TenantContext.clear();
        }
    }

    public Optional<ConsentTemplate> getTemplateByTenantAndTemplateIdAndTemplateVersion(String tenantId, String templateId, int templateVersion) {
        validateInputs(tenantId, "Tenant ID cannot be null or empty");
        validateInputs(templateId, "template ID cannot be null or empty");

        TenantContext.setCurrentTenant(tenantId);
        MongoTemplate tenantMongoTemplate = mongoConfig.getMongoTemplateForTenant(tenantId);

        try {
            Query query = new Query(Criteria.where("templateId").is(templateId).and("version").is(templateVersion));
            ConsentTemplate template = tenantMongoTemplate.findOne(query, ConsentTemplate.class);
            return Optional.ofNullable(template);
        } finally {
            TenantContext.clear();
        }
    }

    public Optional<ConsentTemplate> getTemplateByTenantAndTemplateIdAndBusinessId(String tenantId, String templateId,
                                                                                   String businessId, int version) {
        validateInputs(tenantId, "Tenant ID cannot be null or empty");
        validateInputs(templateId, "template ID cannot be null or empty");

        TenantContext.setCurrentTenant(tenantId);
        MongoTemplate tenantMongoTemplate = mongoConfig.getMongoTemplateForTenant(tenantId);

        try {
            Query query = new Query(Criteria.where("templateId").is(templateId).and("businessId").is(businessId)
                    .and("status").is("PUBLISHED").and("templateStatus").is("ACTIVE").and("version")
                    .is(version));

            ConsentTemplate template = tenantMongoTemplate.findOne(query, ConsentTemplate.class);
            return Optional.ofNullable(template);
        } finally {
            TenantContext.clear();
        }
    }

    public List<ConsentTemplate> getTemplatesByTenantId(String tenantId) {
        validateInputs(tenantId, "Tenant ID cannot be null or empty");

        TenantContext.setCurrentTenant(tenantId);
        MongoTemplate tenantMongoTemplate = mongoConfig.getMongoTemplateForTenant(tenantId);

        try {
            return tenantMongoTemplate.findAll(ConsentTemplate.class);
        } finally {
            TenantContext.clear();
        }
    }

    @Transactional
    public ConsentTemplate createTemplate(String tenantId, CreateTemplateRequest createRequest) throws ConsentException {
        validateInputs(tenantId, "Tenant ID cannot be null or empty");
        validateCreateRequest(createRequest);

        // CRITICAL: Validate that the scan exists and is completed
        validateScanExists(tenantId, createRequest.getScanId());

        TenantContext.setCurrentTenant(tenantId);
        MongoTemplate tenantMongoTemplate = mongoConfig.getMongoTemplateForTenant(tenantId);

        try {
            // Check if template already exists for this scan ID
            if (templateExistsForScan(tenantMongoTemplate, createRequest.getScanId())) {
                throw new IllegalArgumentException("Template already exists for scan ID: " + createRequest.getScanId());
            }

            // Create template from request (using the provided scanId)
            ConsentTemplate template = ConsentTemplate.fromCreateRequest(createRequest, createRequest.getScanId());

            validateTemplatePurposes(template.getPreferences(), template.getStatus(), tenantId);

            // Process preferences and set defaults
            processPreferences(template.getPreferences());

            // Save template
            ConsentTemplate savedTemplate = tenantMongoTemplate.save(template);

            log.info("Successfully created template with ID: {} for scan: {} in tenant: {}",
                    savedTemplate.getId(), createRequest.getScanId(), tenantId);

            return savedTemplate;

        } finally {
            TenantContext.clear();
        }
    }

    /**
     * CRITICAL: Validate that scan exists in scan_results table and is COMPLETED
     */
    private void validateScanExists(String tenantId, String scanId) {
        if (scanId == null || scanId.trim().isEmpty()) {
            throw new IllegalArgumentException("Scan ID is required for template creation");
        }

        TenantContext.setCurrentTenant(tenantId);
        MongoTemplate tenantMongoTemplate = mongoConfig.getMongoTemplateForTenant(tenantId);

        try {
            Query query = new Query(Criteria.where("transactionId").is(scanId));
            ScanResultEntity scanResult = tenantMongoTemplate.findOne(query, ScanResultEntity.class);

            if (scanResult == null) {
                throw new IllegalArgumentException("Scan with ID '" + scanId + "' does not exist");
            }

            if (!"COMPLETED".equals(scanResult.getStatus())) {
                throw new IllegalArgumentException("Scan with ID '" + scanId + "' is not completed. Current status: " + scanResult.getStatus());
            }

        } finally {
            TenantContext.clear();
        }
    }

    private boolean templateExistsForScan(MongoTemplate mongoTemplate, String scanId) {
        Query query = new Query(Criteria.where("scanId").is(scanId));
        return mongoTemplate.exists(query, ConsentTemplate.class);
    }

    private void processPreferences(List<Preference> preferences) {
        if (preferences == null) return;

        LocalDateTime now = LocalDateTime.now();

        for (Preference preference : preferences) {

            if (preference.getPreferenceStatus() == null) {
                preference.setPreferenceStatus(PreferenceStatus.NOTACCEPTED);
            }

            if (preference.getStartDate() == null) {
                preference.setStartDate(now);
            }

            if (preference.getPreferenceValidity() != null && preference.getEndDate() == null) {
                LocalDateTime endDate = calculateEndDate(now, preference.getPreferenceValidity());
                preference.setEndDate(endDate);
            }
        }
    }

    private LocalDateTime calculateEndDate(LocalDateTime startDate, com.example.scanner.dto.Duration duration) {
        switch (duration.getUnit()) {
            case DAYS:
                return startDate.plusDays(duration.getValue());
            case MONTHS:
                return startDate.plusMonths(duration.getValue());
            case YEARS:
                return startDate.plusYears(duration.getValue());
            default:
                return startDate.plusDays(duration.getValue());
        }
    }

    private void validateInputs(String input, String errorMessage) {
        if (input == null || input.trim().isEmpty()) {
            throw new IllegalArgumentException(errorMessage);
        }
    }

    private void validateCreateRequest(CreateTemplateRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("Create template request cannot be null");
        }

        if (request.getScanId() == null || request.getScanId().trim().isEmpty()) {
            throw new IllegalArgumentException("Scan ID is required for template creation");
        }

        // NEW: Validate status is only DRAFT or PUBLISHED
        if (request.getStatus() != null &&
                request.getStatus() != TemplateStatus.DRAFT &&
                request.getStatus() != TemplateStatus.PUBLISHED) {
            throw new IllegalArgumentException("Template status must be either DRAFT or PUBLISHED. INACTIVE status is not allowed for template creation.");
        }

        // Existing validations...
        if (request.getPreferences() != null && request.getPreferences().isEmpty()) {
            throw new IllegalArgumentException("At least one preference must be provided if preferences are specified");
        }

        // Validate supported languages match content map
        if (request.getMultilingual() != null &&
                request.getMultilingual().getSupportedLanguages() != null &&
                request.getMultilingual().getLanguageSpecificContentMap() != null) {

            for (var language : request.getMultilingual().getSupportedLanguages()) {
                if (!request.getMultilingual().getLanguageSpecificContentMap().containsKey(language)) {
                    throw new IllegalArgumentException("Missing content for supported language: " + language);
                }
            }
        }

        log.debug("Create template request validation passed for: {} with scan: {}",
                request.getTemplateName(), request.getScanId());
    }


    /**
     * Update an existing template by creating a new version
     * This is the core versioning logic - creates new version and marks old as UPDATED
     *
     * @param tenantId The tenant identifier
     * @param templateId Logical template ID (not document ID)
     * @param updateRequest The update request with changes
     * @return UpdateTemplateResponse with new version details
     * @throws IllegalArgumentException if template not found or validation fails
     * @throws IllegalStateException if template is in invalid state for update
     */
    @Transactional
    public UpdateTemplateResponse updateTemplate(String tenantId, String templateId, UpdateTemplateRequest updateRequest) throws ConsentException {

        TenantContext.setCurrentTenant(tenantId);
        MongoTemplate tenantMongoTemplate = mongoConfig.getMongoTemplateForTenant(tenantId);

        try {
            // Step 1: Find current ACTIVE template
            Query activeQuery = new Query(Criteria.where("templateId").is(templateId)
                    .and("templateStatus").is(VersionStatus.ACTIVE));
            ConsentTemplate currentActiveTemplate = tenantMongoTemplate.findOne(activeQuery, ConsentTemplate.class);

            if (currentActiveTemplate == null) {
                throw new IllegalArgumentException("Template not found: " + templateId);
            }

            // Step 2: Create new template record
            ConsentTemplate newTemplate = new ConsentTemplate();

            // Keep same: templateId, businessId, scanId
            newTemplate.setTemplateId(currentActiveTemplate.getTemplateId());
            newTemplate.setBusinessId(currentActiveTemplate.getBusinessId());
            newTemplate.setScanId(currentActiveTemplate.getScanId());

            // Set version: current version + 1
            newTemplate.setVersion(currentActiveTemplate.getVersion() + 1);

            // Set templateStatus: ACTIVE
            newTemplate.setTemplateStatus(VersionStatus.ACTIVE);

            // Apply updates from request or keep existing values
            newTemplate.setTemplateName(updateRequest.getTemplateName() != null ?
                    updateRequest.getTemplateName() : currentActiveTemplate.getTemplateName());
            newTemplate.setStatus(updateRequest.getStatus() != null ?
                    updateRequest.getStatus() : currentActiveTemplate.getStatus());
            newTemplate.setMultilingual(updateRequest.getMultilingual() != null ?
                    updateRequest.getMultilingual() : currentActiveTemplate.getMultilingual());
            newTemplate.setUiConfig(updateRequest.getUiConfig() != null ?
                    updateRequest.getUiConfig() : currentActiveTemplate.getUiConfig());
            newTemplate.setPrivacyPolicyDocument(updateRequest.getPrivacyPolicyDocument() != null ?
                    updateRequest.getPrivacyPolicyDocument() : currentActiveTemplate.getPrivacyPolicyDocument());
            newTemplate.setDocumentMeta(updateRequest.getPrivacyPolicyDocumentMeta() != null ?
                    updateRequest.getPrivacyPolicyDocumentMeta() : currentActiveTemplate.getDocumentMeta());
            newTemplate.setPreferences(updateRequest.getPreferences() != null ?
                    updateRequest.getPreferences() : currentActiveTemplate.getPreferences());

            if (updateRequest.getPreferences() != null) {
                validateTemplatePurposes(newTemplate.getPreferences(), newTemplate.getStatus(), tenantId);
            }

            // Set timestamps
            newTemplate.setCreatedAt(Instant.now());
            newTemplate.setUpdatedAt(Instant.now());
            newTemplate.setClassName("com.example.scanner.entity.ConsentTemplate");

            // Step 3: Save new template record
            ConsentTemplate savedNewTemplate = tenantMongoTemplate.save(newTemplate);

            // Step 4: Update old template: templateStatus ACTIVE -> UPDATED
            currentActiveTemplate.setTemplateStatus(VersionStatus.UPDATED);
            currentActiveTemplate.setUpdatedAt(Instant.now());
            tenantMongoTemplate.save(currentActiveTemplate);

            return UpdateTemplateResponse.success(
                    templateId,
                    savedNewTemplate.getId(),
                    savedNewTemplate.getVersion(),
                    currentActiveTemplate.getVersion()
            );

        } finally {
            TenantContext.clear();
        }
    }

    /**
     * Find active template or throw descriptive exception
     */
    private ConsentTemplate findActiveTemplateOrThrow(MongoTemplate mongoTemplate, String templateId, String tenantId) {
        Query query = new Query(Criteria.where("templateId").is(templateId)
                .and("templateStatus").is(VersionStatus.ACTIVE));

        ConsentTemplate activeTemplate = mongoTemplate.findOne(query, ConsentTemplate.class);

        if (activeTemplate == null) {
            log.warn("Active template not found: {} in tenant: {}", templateId, tenantId);
            throw new IllegalArgumentException("Template with ID '" + templateId + "' not found or not active");
        }

        return activeTemplate;
    }

    /**
     * Validate that template can be updated
     */
    private void validateTemplateCanBeUpdated(ConsentTemplate template) {
        // Business rule: Only published templates can be updated to create new versions
        // Draft templates should be edited directly, not versioned
        if (template.getStatus() != TemplateStatus.PUBLISHED) {
            throw new IllegalStateException("Only published templates can be updated to create new versions. " +
                    "Current template status: " + template.getStatus());
        }

        // Ensure template is in ACTIVE state
        if (template.getTemplateStatus() != VersionStatus.ACTIVE) {
            throw new IllegalStateException("Template is not in ACTIVE state. Current status: " + template.getTemplateStatus());
        }
    }

    /**
     * Get template history (all versions) for a logical template ID
     *
     * @param tenantId The tenant identifier
     * @param templateId Logical template ID
     * @return List of all template versions ordered by version DESC (newest first)
     */
    public List<ConsentTemplate> getTemplateHistory(String tenantId, String templateId) {
        validateInputs(tenantId, "Tenant ID cannot be null or empty");
        validateInputs(templateId, "Template ID cannot be null or empty");

        TenantContext.setCurrentTenant(tenantId);
        MongoTemplate tenantMongoTemplate = mongoConfig.getMongoTemplateForTenant(tenantId);

        try {
            Query query = new Query(Criteria.where("templateId").is(templateId))
                    .with(Sort.by(Sort.Direction.DESC, "version"));

            List<ConsentTemplate> history = tenantMongoTemplate.find(query, ConsentTemplate.class);

            if (history.isEmpty()) {
                log.warn("No template versions found for templateId: {} in tenant: {}", templateId, tenantId);
                throw new IllegalArgumentException("Template with ID '" + templateId + "' not found");
            }

            log.info("Retrieved {} versions for template: {} in tenant: {}", history.size(), templateId, tenantId);
            return history;

        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            log.error("Error retrieving template history for templateId: {} in tenant: {}", templateId, tenantId, e);
            throw new RuntimeException("Failed to retrieve template history: " + e.getMessage(), e);
        } finally {
            TenantContext.clear();
        }
    }

    /**
     * Get active template by logical template ID (updated to use new versioning)
     * This replaces/enhances existing methods to work with versioning
     */
    public Optional<ConsentTemplate> getActiveTemplateByTemplateId(String tenantId, String templateId) {
        validateInputs(tenantId, "Tenant ID cannot be null or empty");
        validateInputs(templateId, "Template ID cannot be null or empty");

        TenantContext.setCurrentTenant(tenantId);
        MongoTemplate tenantMongoTemplate = mongoConfig.getMongoTemplateForTenant(tenantId);

        try {
            Query query = new Query(Criteria.where("templateId").is(templateId)
                    .and("templateStatus").is(VersionStatus.ACTIVE));

            ConsentTemplate activeTemplate = tenantMongoTemplate.findOne(query, ConsentTemplate.class);
            return Optional.ofNullable(activeTemplate);

        } finally {
            TenantContext.clear();
        }
    }

    /**
     * Get specific version of a template
     * Used when consent handles reference specific template versions
     */
    public Optional<ConsentTemplate> getTemplateByIdAndVersion(String tenantId, String templateId, Integer version) {
        validateInputs(tenantId, "Tenant ID cannot be null or empty");
        validateInputs(templateId, "Template ID cannot be null or empty");

        if (version == null || version <= 0) {
            throw new IllegalArgumentException("Version must be a positive integer");
        }

        TenantContext.setCurrentTenant(tenantId);
        MongoTemplate tenantMongoTemplate = mongoConfig.getMongoTemplateForTenant(tenantId);

        try {
            Query query = new Query(Criteria.where("templateId").is(templateId)
                    .and("version").is(version));

            ConsentTemplate template = tenantMongoTemplate.findOne(query, ConsentTemplate.class);
            return Optional.ofNullable(template);

        } finally {
            TenantContext.clear();
        }
    }

    /**
     * ✅ UPDATED: Validate template purposes based on template status
     * DRAFT templates: Preferences are optional
     * PUBLISHED templates: Preferences are mandatory and must be validated
     */
    /**
     * Validate template purposes structure
     * - DRAFT templates: No validation, preferences are completely optional
     * - PUBLISHED templates: Validate preference structure if preferences exist
     * - Does NOT validate if preferences list is empty (allowed for both DRAFT and PUBLISHED)
     */
    private void validateTemplatePurposes(List<Preference> preferences, TemplateStatus status, String tenantId) throws ConsentException {
        // Empty preferences list is allowed
        if (preferences == null || preferences.isEmpty()) {
            log.debug("PUBLISHED template has no preferences - allowed");
            return;
        }

        for (Preference preference : preferences) {
            try {
                if (preference.getPurpose() == null || preference.getPurpose().trim().isEmpty()) {
                    throw new ConsentException(
                            ErrorCodes.INVALID_TEMPLATE,
                            ErrorCodes.getDescription(ErrorCodes.INVALID_TEMPLATE),
                            "Each preference in PUBLISHED template must have at least one purpose"
                    );
                }

                if (!categoryService.categoryExists(preference.getPurpose(), tenantId)) {
                    throw new ConsentException(
                            ErrorCodes.INVALID_TEMPLATE,
                            ErrorCodes.getDescription(ErrorCodes.INVALID_TEMPLATE),
                            "Category '" + preference.getPurpose() + "' does not exist in the Category table for this tenant"
                    );
                }

                // Validate isMandatory is set
                if (preference.getIsMandatory() == null) {
                    throw new ConsentException(
                            ErrorCodes.INVALID_TEMPLATE,
                            ErrorCodes.getDescription(ErrorCodes.INVALID_TEMPLATE),
                            "isMandatory field is required for all preferences in PUBLISHED template"
                    );
                }

                log.debug("Preference validation passed for purposes: {}", preference.getPurpose());

            } catch (ConsentException e) {
                // Re-throw ConsentException as-is
                log.error("Template preference validation failed: {}", e.getMessage());
                throw e;
            } catch (Exception e) {
                // Convert any other exception to ConsentException
                log.error("Unexpected error during preference validation", e);
                throw new ConsentException(
                        ErrorCodes.INVALID_TEMPLATE,
                        ErrorCodes.getDescription(ErrorCodes.INVALID_TEMPLATE),
                        "Error validating template preferences: " + e.getMessage()
                );
            }
        }

        log.debug("All {} preferences validated successfully for PUBLISHED template", preferences.size());
    }
}