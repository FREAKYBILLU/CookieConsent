package com.example.scanner.repository;

import com.example.scanner.entity.ConsentTemplate;
import com.example.scanner.enums.VersionStatus;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.mongodb.repository.Query;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.Optional;

@Repository
public interface ConsentTemplateRepository extends MongoRepository<ConsentTemplate, String> {

    // Legacy methods - kept for backward compatibility
    Optional<ConsentTemplate> findByScanId(String scanId);
    List<ConsentTemplate> findByBusinessId(String businessId);
    List<ConsentTemplate> findByStatus(String status);

    @Query("{'businessId': ?0, 'status': ?1}")
    List<ConsentTemplate> findByBusinessIdAndStatus(String businessId, String status);

    @Query("{'scanId': ?0, 'businessId': ?1}")
    Optional<ConsentTemplate> findByScanIdAndBusinessId(String scanId, String businessId);

    // NEW VERSIONING METHODS

    /**
     * Find the active version of a template by logical template ID
     * Critical: Only returns the current active version
     */
    @Query("{'templateId': ?0, 'templateStatus': 'ACTIVE'}")
    Optional<ConsentTemplate> findActiveByTemplateId(String templateId);

    /**
     * Find the active version of a template by logical template ID and business ID
     * Used for business-scoped template access
     */
    @Query("{'templateId': ?0, 'businessId': ?1, 'templateStatus': 'ACTIVE'}")
    Optional<ConsentTemplate> findActiveByTemplateIdAndBusinessId(String templateId, String businessId);

    /**
     * Find all versions of a template ordered by version number (latest first)
     * Used for template history API
     */
    @Query("{'templateId': ?0}")
    List<ConsentTemplate> findAllVersionsByTemplateIdOrderByVersionDesc(String templateId);

    /**
     * Find all active templates for a business
     * Used for business dashboard - shows only current versions
     */
    @Query("{'businessId': ?0, 'templateStatus': 'ACTIVE'}")
    List<ConsentTemplate> findActiveTemplatesByBusinessId(String businessId);

    /**
     * Find all templates (all versions) for a business
     * Used for comprehensive business reporting
     */
    List<ConsentTemplate> findByBusinessIdOrderByTemplateIdAscVersionDesc(String businessId);

    /**
     * Check if a template exists by logical template ID
     * Used for validation before updates
     */
    @Query(value = "{'templateId': ?0}", exists = true)
    boolean existsByTemplateId(String templateId);

    /**
     * Find specific version of a template
     * Used when consent handles reference specific template versions
     */
    @Query("{'templateId': ?0, 'version': ?1}")
    Optional<ConsentTemplate> findByTemplateIdAndVersion(String templateId, Integer version);

    /**
     * Find templates by scan ID (all versions)
     * Used to see all template versions created from a specific scan
     */
    List<ConsentTemplate> findByScanIdOrderByVersionDesc(String scanId);

    /**
     * Get maximum version number for a template
     * Used for version validation and increment logic
     */
    @Query(value = "{'templateId': ?0}", fields = "{'version': 1}", sort = "{'version': -1}")
    Optional<ConsentTemplate> findMaxVersionByTemplateId(String templateId);

    /**
     * Count total versions for a template
     * Used for analytics and validation
     */
    @Query(value = "{'templateId': ?0}", count = true)
    long countVersionsByTemplateId(String templateId);

    /**
     * Find templates by status and template status combination
     * Used for administrative queries
     */
    @Query("{'status': ?0, 'templateStatus': ?1}")
    List<ConsentTemplate> findByStatusAndTemplateStatus(String status, VersionStatus templateStatus);

    /**
     * Find all active published templates for a business
     * Used for consent handle creation (only published active templates can be used)
     */
    @Query("{'businessId': ?0, 'status': 'PUBLISHED', 'templateStatus': 'ACTIVE'}")
    List<ConsentTemplate> findActivePublishedTemplatesByBusinessId(String businessId);
}