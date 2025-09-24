package com.example.scanner.service;

import com.example.scanner.config.MultiTenantMongoConfig;
import com.example.scanner.config.TenantContext;
import com.example.scanner.dto.CreateTemplateRequest;
import com.example.scanner.dto.Preference;
import com.example.scanner.entity.ConsentTemplate;
import com.example.scanner.entity.ScanResultEntity;
import com.example.scanner.enums.PreferenceStatus;
import com.example.scanner.enums.TemplateStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class ConsentTemplateService {

    private final MultiTenantMongoConfig mongoConfig;

    public Optional<ConsentTemplate> getTemplateByTenantAndScanId(String tenantId, String scanId) {
        validateInputs(tenantId, "Tenant ID cannot be null or empty");
        validateInputs(scanId, "Scan ID cannot be null or empty");

        TenantContext.setCurrentTenant(tenantId);
        MongoTemplate tenantMongoTemplate = mongoConfig.getMongoTemplateForTenant(tenantId);

        try {
            Query query = new Query(Criteria.where("scanId").is(scanId));
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
    public ConsentTemplate createTemplate(String tenantId, CreateTemplateRequest createRequest) {
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
            if (preference.getPreferenceId() == null || preference.getPreferenceId().isEmpty()) {
                preference.setPreferenceId(UUID.randomUUID().toString());
            }

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
}