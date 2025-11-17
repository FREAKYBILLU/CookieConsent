package com.example.scanner.service;

import com.example.scanner.config.MultiTenantMongoConfig;
import com.example.scanner.config.TenantContext;
import com.example.scanner.constants.Constants;
import com.example.scanner.constants.ErrorCodes;
import com.example.scanner.dto.Preference;
import com.example.scanner.dto.request.CreateHandleRequest;
import com.example.scanner.dto.response.ConsentHandleResponse;
import com.example.scanner.dto.response.GetHandleResponse;
import com.example.scanner.dto.response.PreferenceWithCookies;
import com.example.scanner.entity.CookieConsentHandle;
import com.example.scanner.entity.ConsentTemplate;
import com.example.scanner.entity.CookieEntity;
import com.example.scanner.entity.ScanResultEntity;
import com.example.scanner.enums.ConsentHandleStatus;
import com.example.scanner.exception.ConsentHandleExpiredException;
import com.example.scanner.exception.ScannerException;
import com.example.scanner.repository.ConsentHandleRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;
import org.springframework.util.ObjectUtils;

import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class ConsentHandleService {

    private final ConsentHandleRepository consentHandleRepository;
    private final ConsentTemplateService consentTemplateService;
    private final MultiTenantMongoConfig mongoConfig;
    private final AuditService auditService;

    @Value("${consent.handle.expiry.minutes:15}")
    private int handleExpiryMinutes;

    public ConsentHandleResponse createConsentHandle(String tenantId, CreateHandleRequest request, Map<String, String> headers)
            throws ScannerException {

        if (tenantId == null || tenantId.trim().isEmpty()) {
            throw new ScannerException(ErrorCodes.VALIDATION_ERROR,
                    "Tenant ID is required",
                    "Missing X-Tenant-ID header");
        }

        auditService.logConsentHandleCreationInitiated(tenantId, headers.get(Constants.BUSINESS_ID_HEADER),"pending");

        TenantContext.setCurrentTenant(tenantId);

        try {
            validateTemplate(tenantId, request.getTemplateId(), request.getTemplateVersion());

            CookieConsentHandle existingHandle = consentHandleRepository.findActiveConsentHandle(
                    request.getCustomerIdentifiers().getValue(),
                    request.getUrl(),
                    request.getTemplateId(),
                    request.getTemplateVersion(),
                    tenantId
            );

            if (existingHandle != null) {
                log.info("Returning existing consent handle: {} for deviceId: {}, url: {}, templateId: {}, version: {}",
                        existingHandle.getConsentHandleId(),
                        request.getCustomerIdentifiers().getValue(),
                        request.getUrl(),
                        request.getTemplateId(),
                        request.getTemplateVersion());

                return ConsentHandleResponse.builder()
                        .consentHandleId(existingHandle.getConsentHandleId())
                        .message("Existing Consent Handle returned!")
                        .txnId(headers.get(Constants.TXN_ID))
                        .isNewHandle(false)
                        .build();
            }

            String consentHandleId = UUID.randomUUID().toString();

            CookieConsentHandle consentHandle = new CookieConsentHandle(
                    consentHandleId,
                    headers.get(Constants.BUSINESS_ID_HEADER),
                    headers.get(Constants.TXN_ID),
                    request.getTemplateId(),
                    request.getTemplateVersion(),
                    request.getUrl(),
                    request.getCustomerIdentifiers(),
                    ConsentHandleStatus.PENDING,
                    handleExpiryMinutes
            );

            CookieConsentHandle savedHandle = this.consentHandleRepository.save(consentHandle, tenantId);

            // Log handle created
            auditService.logConsentHandleCreated(tenantId, headers.get(Constants.BUSINESS_ID_HEADER), consentHandleId);

            log.info("Created new consent handle with ID: {} for template: {} and tenant: {}",
                    consentHandleId, request.getTemplateId(), tenantId);

            return ConsentHandleResponse.builder()
                    .consentHandleId(savedHandle.getConsentHandleId())
                    .message("Consent Handle Created successfully!")
                    .txnId(headers.get(Constants.TXN_ID))
                    .isNewHandle(true)
                    .build();

        } catch (IllegalStateException e) {
            if (e.getMessage().contains("Database") && e.getMessage().contains("does not exist")) {
                log.error("Database does not exist for tenant: {}", tenantId);
                throw new ScannerException(ErrorCodes.VALIDATION_ERROR,
                        "Invalid tenant - database does not exist",
                        "Database 'template_" + tenantId + "' does not exist. Please check the tenant ID.");
            }
            throw e;
        } catch (DataAccessException e) {
            log.error("Database access error while creating consent handle for tenant: {}", tenantId, e);
            throw new ScannerException(ErrorCodes.INTERNAL_ERROR,
                    "Failed to access database",
                    e.getMessage());
        } catch (Exception e) {
            log.error("Error creating consent handle for tenant: {}", tenantId, e);
            throw new ScannerException(ErrorCodes.INTERNAL_ERROR,
                    "Failed to create consent handle",
                    e.getMessage());
        } finally {
            TenantContext.clear();
        }
    }

    public GetHandleResponse getConsentHandleById(String consentHandleId, String tenantId)
            throws ScannerException {

        if (tenantId == null || tenantId.trim().isEmpty()) {
            throw new ScannerException(ErrorCodes.VALIDATION_ERROR,
                    "Tenant ID is required",
                    "Missing tenant ID for consent handle retrieval");
        }

        TenantContext.setCurrentTenant(tenantId);
        MongoTemplate mongoTemplate = mongoConfig.getMongoTemplateForTenant(tenantId);

        try {
            // Fetch consent handle
            CookieConsentHandle consentHandle = this.consentHandleRepository.getByConsentHandleId(consentHandleId, tenantId);
            if (ObjectUtils.isEmpty(consentHandle)) {
                log.warn("Consent handle not found: {}", consentHandleId);
                throw new ScannerException(ErrorCodes.NOT_FOUND,
                        "Consent handle not found",
                        "No consent handle found with ID: " + consentHandleId);
            }

            validateNotExpired(consentHandle, tenantId);

            // Get template information
            Optional<ConsentTemplate> templateOpt = consentTemplateService.getTemplateByTenantAndTemplateIdAndTemplateVersion(
                    tenantId, consentHandle.getTemplateId(), consentHandle.getTemplateVersion());

            if (templateOpt.isEmpty()) {
                log.warn("Template not found for consent handle: {}", consentHandleId);
                throw new ScannerException(ErrorCodes.NOT_FOUND,
                        "Template not found",
                        "Template not found for consent handle: " + consentHandleId);
            }

            ConsentTemplate template = templateOpt.get();

            // STEP 1: Fetch cookies from scan results
            Map<String, List<CookieEntity>> cookiesByCategory = fetchAndCategorizeCookies(
                    template.getScanId(),
                    mongoTemplate
            );

            // STEP 2: Create PreferenceWithCookies by matching purpose with cookie category
            List<PreferenceWithCookies> preferencesWithCookies = mapCookiesToPreferences(
                    template.getPreferences(),
                    cookiesByCategory
            );

            // STEP 3: Build response
            GetHandleResponse response = GetHandleResponse.builder()
                    .consentHandleId(consentHandle.getConsentHandleId())
                    .templateId(template.getTemplateId())
                    .templateName(template.getTemplateName())
                    .templateVersion(consentHandle.getTemplateVersion())
                    .url(consentHandle.getUrl())
                    .businessId(consentHandle.getBusinessId())
                    .multilingual(template.getMultilingual())
                    .preferences(preferencesWithCookies)  // Use the new wrapper
                    .customerIdentifiers(consentHandle.getCustomerIdentifiers())
                    .status(consentHandle.getStatus())
                    .build();

            log.info("Retrieved consent handle: {} with {} preferences and cookies for tenant: {}",
                    consentHandleId, preferencesWithCookies.size(), tenantId);

            return response;

        } finally {
            TenantContext.clear();
        }
    }

    /**
     * Fetch all cookies from scan result and group them by category
     */
    private Map<String, List<CookieEntity>> fetchAndCategorizeCookies(
            String scanId,
            MongoTemplate mongoTemplate) {

        if (scanId == null || scanId.trim().isEmpty()) {
            log.warn("No scanId provided, returning empty cookie map");
            return Collections.emptyMap();
        }

        try {
            // Fetch scan result
            Query query = new Query(Criteria.where("transactionId").is(scanId));
            ScanResultEntity scanResult = mongoTemplate.findOne(query, ScanResultEntity.class);

            if (scanResult == null || scanResult.getCookiesBySubdomain() == null) {
                log.warn("No scan result or cookies found for scanId: {}", scanId);
                return Collections.emptyMap();
            }

            // Flatten all cookies from all subdomains
            List<CookieEntity> allCookies = scanResult.getCookiesBySubdomain().values()
                    .stream()
                    .flatMap(List::stream)
                    .collect(Collectors.toList());

            log.info("Found {} total cookies across all subdomains for scanId: {}",
                    allCookies.size(), scanId);

            // Group cookies by category
            Map<String, List<CookieEntity>> cookiesByCategory = allCookies.stream()
                    .filter(cookie -> cookie.getCategory() != null && !cookie.getCategory().trim().isEmpty())
                    .collect(Collectors.groupingBy(CookieEntity::getCategory));

            log.info("Categorized cookies into {} categories", cookiesByCategory.size());

            return cookiesByCategory;

        } catch (Exception e) {
            log.error("Error fetching cookies for scanId: {}", scanId, e);
            return Collections.emptyMap();
        }
    }

    /**
     * Map cookies to preferences based on matching purpose with cookie category
     */
    private List<PreferenceWithCookies> mapCookiesToPreferences(
            List<Preference> preferences,
            Map<String, List<CookieEntity>> cookiesByCategory) {

        if (preferences == null || preferences.isEmpty()) {
            log.warn("No preferences to map cookies to");
            return Collections.emptyList();
        }


        return preferences.stream()
                .map(preference -> {
                    String categoryKey = preference.getPurpose();
                    List<CookieEntity> matchingCookies = cookiesByCategory.getOrDefault(
                            categoryKey,
                            Collections.emptyList()
                    );

                    log.debug("Mapped {} cookies to preference category: {}",
                            matchingCookies.size(), categoryKey);

                    return PreferenceWithCookies.from(preference, matchingCookies);
                })
                .collect(Collectors.toList());
    }

    private void validateTemplate(String tenantId, String templateId, int templateVersion) throws ScannerException {
        // Check if template exists using the existing ConsentTemplateService
        Optional<ConsentTemplate> templateOpt = consentTemplateService.getTemplateByTenantAndTemplateIdAndBusinessId(tenantId, templateId, templateVersion);

        if (templateOpt.isEmpty()) {
            throw new ScannerException(ErrorCodes.NOT_FOUND,
                    "Template not found",
                    "Template with ID " + templateId + " and version "+ templateVersion +" with status PUBLISHED and Template Status ACTIVE does not exist for tenant " + tenantId);
        }

        ConsentTemplate template = templateOpt.get();

        // Validate template is published
        if (!"PUBLISHED".equals(template.getStatus().name())) {
            throw new ScannerException(ErrorCodes.INVALID_STATE_ERROR,
                    "Template is not published",
                    "Template " + templateId + " has status: " + template.getStatus());
        }
    }

    private void validateNotExpired(CookieConsentHandle handle, String tenantId) {
        if (handle.isExpired()) {
            handle.setStatus(ConsentHandleStatus.REQ_EXPIRED);
            consentHandleRepository.save(handle, tenantId);

            throw new ConsentHandleExpiredException(
                    ErrorCodes.CONSENT_HANDLE_EXPIRED,
                    "Consent handle has expired",
                    "Consent handle " + handle.getConsentHandleId() + " expired at " + handle.getExpiresAt()
            );
        }
    }
}