package com.example.scanner.repository;

import com.example.scanner.dto.CustomerIdentifiers;
import com.example.scanner.entity.Consent;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface ConsentRepository extends MongoRepository<Consent, String> {

    // ==== CUSTOM METHODS IMPLEMENTED IN ConsentRepositoryImpl ====

    /**
     * Check if consent exists by template, version and customer
     * Legacy method for backward compatibility
     */
    Consent existByTemplateIdAndTemplateVersionAndCustomerIdentifiers(
            String templateId, Integer templateVersion, CustomerIdentifiers customerIdentifiers, String tenantId);

    /**
     * Find the active version of a consent by logical consent ID
     * Critical: Only returns the current active version
     */
    Consent findActiveByConsentId(String consentId, String tenantId);

    /**
     * Find all versions of a consent for a specific tenant
     * Used for tenant-scoped consent history
     */
    List<Consent> findAllVersionsByConsentId(String consentId, String tenantId);

    /**
     * Find all active consents for a customer
     * Used for customer consent dashboard
     */
    List<Consent> findActiveConsentsByCustomer(String customerValue, String tenantId);

    /**
     * Find all consents (all versions) for a customer
     * Used for comprehensive customer consent history
     */
    List<Consent> findAllConsentsByCustomerOrderByConsentIdAscVersionDesc(String customerValue, String tenantId);

    /**
     * Find active consents for a business
     * Used for business consent reporting
     */
    List<Consent> findActiveConsentsByBusinessId(String businessId, String tenantId);

    /**
     * Find consents by template ID and version
     * Used to find all consents that used a specific template version
     */
    List<Consent> findByTemplateIdAndVersion(String templateId, Integer templateVersion, String tenantId);

    /**
     * Find active consents by template ID
     * Used to see all active consents using a specific template
     */
    List<Consent> findActiveConsentsByTemplateId(String templateId, String tenantId);

    /**
     * Check if a consent exists by logical consent ID
     * Used for validation before updates
     */
    boolean existsByConsentId(String consentId, String tenantId);

    /**
     * Find specific version of a consent
     * Used for historical consent lookups
     */
    Optional<Consent> findByConsentIdAndVersion(String consentId, Integer version, String tenantId);

    /**
     * Get maximum version number for a consent
     * Used for version validation and increment logic
     */
    Optional<Consent> findMaxVersionByConsentId(String consentId, String tenantId);

    /**
     * Count total versions for a consent
     * Used for analytics and validation
     */
    long countVersionsByConsentId(String consentId, String tenantId);

    /**
     * Find consents by consent handle ID
     * Used to trace which consent handle created which consent
     */
    Optional<Consent> findByConsentHandleId(String consentHandleId, String tenantId);

    /**
     * Find expired active consents
     * Used for consent expiry processing jobs
     */
    List<Consent> findExpiredActiveConsents(LocalDateTime currentTime, String tenantId);

    /**
     * Save consent with tenant context
     * Custom method to ensure proper tenant isolation
     */
    Consent save(Consent consent, String tenantId);
}