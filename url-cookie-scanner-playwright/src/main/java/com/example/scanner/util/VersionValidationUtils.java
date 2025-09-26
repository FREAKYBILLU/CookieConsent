package com.example.scanner.util;

import com.example.scanner.constants.ErrorCodes;
import com.example.scanner.entity.Consent;
import com.example.scanner.entity.ConsentTemplate;
import com.example.scanner.enums.VersionStatus;
import com.example.scanner.exception.ConsentException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Comprehensive validation utilities for versioning system
 * Handles edge cases, data integrity checks, and business rule validations
 */
@Slf4j
public final class VersionValidationUtils {

    private VersionValidationUtils() {
        throw new UnsupportedOperationException("Utility class cannot be instantiated");
    }

    // ==== TEMPLATE VALIDATION METHODS ====

    /**
     * Validate template version integrity before creating new version
     * Comprehensive checks to prevent data corruption and business rule violations
     *
     * @param mongoTemplate MongoDB template for tenant
     * @param templateId Logical template ID
     * @param tenantId Tenant identifier
     * @throws ConsentException if validation fails
     */
    public static void validateTemplateVersionIntegrity(MongoTemplate mongoTemplate, String templateId, String tenantId) throws ConsentException {
        log.debug("Validating template version integrity for templateId: {} in tenant: {}", templateId, tenantId);

        // CRITICAL: Ensure only one active version exists
        validateSingleActiveTemplate(mongoTemplate, templateId);

        // Check for version sequence gaps
        validateTemplateVersionSequence(mongoTemplate, templateId);

        // Validate update frequency (business rule)
        validateTemplateUpdateFrequency(mongoTemplate, templateId);

        // Check for concurrent modifications
        validateNoConcurrentTemplateModifications(mongoTemplate, templateId);

        log.debug("Template version integrity validation passed for templateId: {}", templateId);
    }

    /**
     * CRITICAL: Ensure exactly one active version per template
     * This prevents data corruption and ensures referential integrity
     */
    private static void validateSingleActiveTemplate(MongoTemplate mongoTemplate, String templateId) throws ConsentException {
        Query query = new Query(Criteria.where("templateId").is(templateId)
                .and("templateStatus").is(VersionStatus.ACTIVE));

        long activeCount = mongoTemplate.count(query, ConsentTemplate.class);

        if (activeCount == 0) {
            log.error("CRITICAL: No active version found for templateId: {}", templateId);
            throw new ConsentException(ErrorCodes.TEMPLATE_NO_ACTIVE_VERSION);
        }

        if (activeCount > 1) {
            log.error("CRITICAL: Multiple active versions found for templateId: {}. Count: {}", templateId, activeCount);

            // Auto-healing attempt: Log all active versions for investigation
            List<ConsentTemplate> activeVersions = mongoTemplate.find(query, ConsentTemplate.class);
            activeVersions.forEach(template ->
                    log.error("CRITICAL: Active template version found - ID: {}, Version: {}, Created: {}",
                            template.getId(), template.getVersion(), template.getCreatedAt())
            );

            throw new ConsentException(ErrorCodes.TEMPLATE_MULTIPLE_ACTIVE_VERSIONS);
        }

        log.debug("Template active version validation passed: exactly 1 active version found");
    }

    /**
     * Validate version sequence integrity (no gaps or duplicates)
     */
    private static void validateTemplateVersionSequence(MongoTemplate mongoTemplate, String templateId) {
        Query query = new Query(Criteria.where("templateId").is(templateId))
                .with(org.springframework.data.domain.Sort.by(org.springframework.data.domain.Sort.Direction.ASC, "version"));

        List<ConsentTemplate> allVersions = mongoTemplate.find(query, ConsentTemplate.class);

        if (allVersions.isEmpty()) {
            return; // No versions to validate
        }

        AtomicInteger expectedVersion = new AtomicInteger(1);
        allVersions.forEach(template -> {
            if (!template.getVersion().equals(expectedVersion.get())) {
                log.warn("Version sequence gap detected for templateId: {}. Expected: {}, Found: {}",
                        templateId, expectedVersion.get(), template.getVersion());

                // This is a warning, not a critical error - gaps can occur from deletions
                // But we should log for audit purposes
            }
            expectedVersion.set(template.getVersion() + 1);
        });
    }

    /**
     * Validate update frequency to prevent abuse
     * Business rule: Max 5 updates per hour per template
     */
    private static void validateTemplateUpdateFrequency(MongoTemplate mongoTemplate, String templateId) throws ConsentException {
        Instant oneHourAgo = Instant.now().minus(1, ChronoUnit.HOURS);

        Query query = new Query(Criteria.where("templateId").is(templateId)
                .and("createdAt").gte(oneHourAgo));

        long recentUpdates = mongoTemplate.count(query, ConsentTemplate.class);

        // Allow up to 5 versions created in the last hour (current active + 4 recent updates)
        if (recentUpdates > 5) {
            log.warn("Update frequency limit exceeded for templateId: {}. Recent updates: {}", templateId, recentUpdates);
            throw new ConsentException(ErrorCodes.UPDATE_FREQUENCY_LIMIT_EXCEEDED);
        }
    }

    /**
     * Check for concurrent modifications (race condition detection)
     * Look for multiple templates created within a very short time window
     */
    private static void validateNoConcurrentTemplateModifications(MongoTemplate mongoTemplate, String templateId) throws ConsentException {
        Instant twoMinutesAgo = Instant.now().minus(2, ChronoUnit.MINUTES);

        Query query = new Query(Criteria.where("templateId").is(templateId)
                .and("createdAt").gte(twoMinutesAgo));

        long veryRecentVersions = mongoTemplate.count(query, ConsentTemplate.class);

        // If more than 2 versions created in last 2 minutes, likely concurrent modification
        if (veryRecentVersions > 2) {
            log.warn("Possible concurrent modification detected for templateId: {}. Very recent versions: {}",
                    templateId, veryRecentVersions);
            throw new ConsentException(ErrorCodes.CONCURRENT_VERSION_CREATION);
        }
    }

    // ==== CONSENT VALIDATION METHODS ====

    /**
     * Validate consent version integrity before creating new version
     *
     * @param mongoTemplate MongoDB template for tenant
     * @param consentId Logical consent ID
     * @param tenantId Tenant identifier
     * @throws ConsentException if validation fails
     */
    public static void validateConsentVersionIntegrity(MongoTemplate mongoTemplate, String consentId, String tenantId) throws ConsentException {
        log.debug("Validating consent version integrity for consentId: {} in tenant: {}", consentId, tenantId);

        // CRITICAL: Ensure only one active version exists
        validateSingleActiveConsent(mongoTemplate, consentId);

        // Check for version sequence gaps
        validateConsentVersionSequence(mongoTemplate, consentId);

        // Validate update frequency (business rule)
        validateConsentUpdateFrequency(mongoTemplate, consentId);

        // Check for concurrent modifications
        validateNoConcurrentConsentModifications(mongoTemplate, consentId);

        log.debug("Consent version integrity validation passed for consentId: {}", consentId);
    }

    /**
     * CRITICAL: Ensure exactly one active version per consent
     */
    private static void validateSingleActiveConsent(MongoTemplate mongoTemplate, String consentId) throws ConsentException {
        Query query = new Query(Criteria.where("consentId").is(consentId)
                .and("consentStatus").is(VersionStatus.ACTIVE));

        long activeCount = mongoTemplate.count(query, Consent.class);

        if (activeCount == 0) {
            log.error("CRITICAL: No active version found for consentId: {}", consentId);
            throw new ConsentException(ErrorCodes.CONSENT_NOT_FOUND);
        }

        if (activeCount > 1) {
            log.error("CRITICAL: Multiple active versions found for consentId: {}. Count: {}", consentId, activeCount);

            // Auto-healing attempt: Log all active versions for investigation
            List<Consent> activeVersions = mongoTemplate.find(query, Consent.class);
            activeVersions.forEach(consent ->
                    log.error("CRITICAL: Active consent version found - ID: {}, Version: {}, Created: {}",
                            consent.getId(), consent.getVersion(), consent.getCreatedAt())
            );

            throw new ConsentException(ErrorCodes.CONSENT_MULTIPLE_ACTIVE_VERSIONS);
        }

        log.debug("Consent active version validation passed: exactly 1 active version found");
    }

    /**
     * Validate consent version sequence integrity
     */
    private static void validateConsentVersionSequence(MongoTemplate mongoTemplate, String consentId) {
        Query query = new Query(Criteria.where("consentId").is(consentId))
                .with(org.springframework.data.domain.Sort.by(org.springframework.data.domain.Sort.Direction.ASC, "version"));

        List<Consent> allVersions = mongoTemplate.find(query, Consent.class);

        if (allVersions.isEmpty()) {
            return; // No versions to validate
        }

        AtomicInteger expectedVersion = new AtomicInteger(1);
        allVersions.forEach(consent -> {
            if (!consent.getVersion().equals(expectedVersion.get())) {
                log.warn("Consent version sequence gap detected for consentId: {}. Expected: {}, Found: {}",
                        consentId, expectedVersion.get(), consent.getVersion());
            }
            expectedVersion.set(consent.getVersion() + 1);
        });
    }

    /**
     * Validate consent update frequency
     * Business rule: Max 3 updates per day per consent (to prevent abuse)
     */
    private static void validateConsentUpdateFrequency(MongoTemplate mongoTemplate, String consentId) throws ConsentException {
        Instant oneDayAgo = Instant.now().minus(1, ChronoUnit.DAYS);

        Query query = new Query(Criteria.where("consentId").is(consentId)
                .and("createdAt").gte(oneDayAgo));

        long recentUpdates = mongoTemplate.count(query, Consent.class);

        // Allow up to 3 versions created in the last day
        if (recentUpdates > 3) {
            log.warn("Consent update frequency limit exceeded for consentId: {}. Recent updates: {}", consentId, recentUpdates);
            throw new ConsentException(ErrorCodes.UPDATE_FREQUENCY_LIMIT_EXCEEDED);
        }
    }

    /**
     * Check for concurrent consent modifications
     */
    private static void validateNoConcurrentConsentModifications(MongoTemplate mongoTemplate, String consentId) throws ConsentException {
        Instant fiveMinutesAgo = Instant.now().minus(5, ChronoUnit.MINUTES);

        Query query = new Query(Criteria.where("consentId").is(consentId)
                .and("createdAt").gte(fiveMinutesAgo));

        long veryRecentVersions = mongoTemplate.count(query, Consent.class);

        // If more than 2 versions created in last 5 minutes, likely concurrent modification
        if (veryRecentVersions > 2) {
            log.warn("Possible concurrent consent modification detected for consentId: {}. Very recent versions: {}",
                    consentId, veryRecentVersions);
            throw new ConsentException(ErrorCodes.CONCURRENT_VERSION_CREATION);
        }
    }

    // ==== CROSS-CUTTING VALIDATION METHODS ====

    /**
     * Validate immutable fields haven't been modified
     * Critical for maintaining data integrity across versions
     *
     * @param original Original entity
     * @param updated Updated entity
     * @param entityType Entity type name for error messages
     * @throws ConsentException if immutable fields were modified
     */
    public static void validateImmutableFields(Object original, Object updated, String entityType) {
        // This would use reflection to check immutable fields
        // For now, we'll implement specific checks in the calling services
        log.debug("Validating immutable fields for {}", entityType);

        // Template-specific immutable field validation would go here
        // Consent-specific immutable field validation would go here
    }

    /**
     * Validate tenant isolation - ensure entities belong to correct tenant
     * Critical security check to prevent cross-tenant data access
     *
     * @param entityTenantId Tenant ID from entity
     * @param requestTenantId Tenant ID from request
     * @param entityType Entity type for error messages
     * @throws ConsentException if tenant isolation is violated
     */
    public static void validateTenantIsolation(String entityTenantId, String requestTenantId, String entityType) throws ConsentException {
        if (entityTenantId == null || requestTenantId == null) {
            log.error("Tenant ID validation failed - null values detected. Entity: {}, Request: {}",
                    entityTenantId, requestTenantId);
            throw new ConsentException(ErrorCodes.TENANT_ISOLATION_VIOLATION);
        }

        if (!entityTenantId.equals(requestTenantId)) {
            log.error("SECURITY: Tenant isolation violation detected. Entity tenant: {}, Request tenant: {}, Type: {}",
                    entityTenantId, requestTenantId, entityType);
            throw new ConsentException(ErrorCodes.TENANT_ISOLATION_VIOLATION);
        }

        log.debug("Tenant isolation validation passed for {}", entityType);
    }

    /**
     * Validate business hours restriction (if enabled)
     * Some organizations may want to restrict updates during business hours
     *
     * @param allowDuringBusinessHours Whether updates are allowed during business hours
     * @throws ConsentException if update is attempted during restricted hours
     */
    public static void validateBusinessHoursRestriction(boolean allowDuringBusinessHours) throws ConsentException {
        if (!allowDuringBusinessHours) {
            // Simple implementation - check if current time is business hours (9 AM - 5 PM UTC)
            java.time.LocalTime now = java.time.LocalTime.now(java.time.ZoneOffset.UTC);
            java.time.LocalTime businessStart = java.time.LocalTime.of(9, 0);
            java.time.LocalTime businessEnd = java.time.LocalTime.of(17, 0);

            if (now.isAfter(businessStart) && now.isBefore(businessEnd)) {
                log.warn("Update attempted during restricted business hours: {}", now);
                throw new ConsentException(ErrorCodes.UPDATE_NOT_ALLOWED_BUSINESS_HOURS);
            }
        }
    }
}