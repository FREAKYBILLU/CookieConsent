package com.example.scanner.service;

import com.example.scanner.config.MultiTenantMongoConfig;
import com.example.scanner.config.TenantContext;
import com.example.scanner.constants.ErrorCodes;
import com.example.scanner.dto.Preference;
import com.example.scanner.dto.request.CreateConsentRequest;
import com.example.scanner.dto.request.DashboardRequest;
import com.example.scanner.dto.request.UpdateConsentRequest;
import com.example.scanner.dto.response.ConsentCreateResponse;
import com.example.scanner.dto.response.ConsentTokenValidateResponse;
import com.example.scanner.dto.response.DashboardResponse;
import com.example.scanner.dto.response.UpdateConsentResponse;
import com.example.scanner.entity.Consent;
import com.example.scanner.entity.ConsentHandle;
import com.example.scanner.entity.ConsentTemplate;
import com.example.scanner.entity.ScanResultEntity;
import com.example.scanner.enums.*;
import com.example.scanner.exception.ConsentException;
import com.example.scanner.repository.ConsentHandleRepository;
import com.example.scanner.repository.impl.ConsentRepositoryImpl;
import com.example.scanner.util.InstantTypeAdapter;
import com.example.scanner.util.LocalDateTypeAdapter;
import com.example.scanner.util.TokenUtility;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.ObjectUtils;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class ConsentService {

    private final ConsentHandleRepository consentHandleRepository;
    private final ConsentTemplateService templateService;
    private final TokenUtility tokenUtility;
    private final ConsentRepositoryImpl consentRepositoryImpl;
    private final MultiTenantMongoConfig mongoConfig;

    // ============================================
    // PUBLIC API METHODS
    // ============================================

    public ConsentCreateResponse createConsentByConsentHandleId(CreateConsentRequest request, String tenantId) throws Exception {
        log.info("Processing consent creation for handle: {}, tenant: {}", request.getConsentHandleId(), tenantId);

        // Validate preferences present
        if (request.getPreferencesStatus() == null || request.getPreferencesStatus().isEmpty()) {
            throw new ConsentException(ErrorCodes.VALIDATION_ERROR,
                    ErrorCodes.getDescription(ErrorCodes.VALIDATION_ERROR),
                    "Preferences status cannot be empty");
        }

        // Validate and get consent handle
        ConsentHandle consentHandle = validateConsentHandle(request.getConsentHandleId(), tenantId);

        // Check for existing consent
        Consent existingConsent = consentRepositoryImpl.existByTemplateIdAndTemplateVersionAndCustomerIdentifiers(
                consentHandle.getTemplateId(), consentHandle.getTemplateVersion(),
                consentHandle.getCustomerIdentifiers(), tenantId, request.getConsentHandleId());

        if (existingConsent != null) {
            log.info("Consent already exists for template: {}", consentHandle.getTemplateId());
            return ConsentCreateResponse.builder()
                    .consentId(existingConsent.getConsentId())
                    .consentJwtToken(existingConsent.getConsentJwtToken())
                    .message("Consent already exists!")
                    .consentExpiry(existingConsent.getEndDate())
                    .build();
        }

        // Get template
        ConsentTemplate template = getTemplate(consentHandle, tenantId);

        // Validate purposes and mandatory check
        validatePurposes(template.getPreferences(), request.getPreferencesStatus(), true);

        // Process preferences (returns only matched ones for creation)
        List<Preference> processedPreferences = processPreferencesForCreation(
                template.getPreferences(),
                request.getPreferencesStatus()
        );

        // Build and save consent
        Consent consent = buildNewConsent(consentHandle, template, processedPreferences,
                request.getLanguagePreference());

        String consentToken = generateConsentToken(consent);
        consent.setConsentJwtToken(consentToken);

        consentRepositoryImpl.save(consent, tenantId);

        consentHandle.setStatus(ConsentHandleStatus.USED);
        consentHandleRepository.save(consentHandle, tenantId);

        log.info("Successfully created consent: {}", consent.getConsentId());

        return ConsentCreateResponse.builder()
                .consentId(consent.getConsentId())
                .consentJwtToken(consentToken)
                .message("Consent created successfully!")
                .consentExpiry(consent.getEndDate())
                .build();
    }

    @Transactional
    public UpdateConsentResponse updateConsent(String consentId, UpdateConsentRequest updateRequest, String tenantId)
            throws Exception {
        log.info("Processing consent update for consentId: {}, tenant: {}", consentId, tenantId);

        // Validate inputs
        validateUpdateInputs(consentId, updateRequest, tenantId);

        // Validate consent handle
        ConsentHandle consentHandle = validateConsentHandle(updateRequest.getConsentHandleId(), tenantId);

        // Find active consent
        Consent activeConsent = findActiveConsentOrThrow(consentId, tenantId);

        // Validate update is allowed
        validateConsentCanBeUpdated(activeConsent, consentHandle);

        // Get template
        ConsentTemplate template = getTemplate(consentHandle, tenantId);

        // Create new version
        Consent newVersion = createNewConsentVersion(activeConsent, updateRequest, consentHandle, template);

        // Save and return response
        return saveConsentUpdate(activeConsent, newVersion, consentHandle, tenantId);
    }

    public List<Consent> getConsentHistory(String consentId, String tenantId) throws Exception {
        validateBasicInputs(consentId, tenantId, "Consent ID");

        List<Consent> history = consentRepositoryImpl.findAllVersionsByConsentId(consentId, tenantId);

        if (history.isEmpty()) {
            throw new ConsentException(ErrorCodes.CONSENT_NOT_FOUND,
                    ErrorCodes.getDescription(ErrorCodes.CONSENT_NOT_FOUND),
                    "No consent versions found for consentId: " + consentId);
        }

        log.info("Retrieved {} versions for consent: {}", history.size(), consentId);
        return history;
    }

    public Optional<Consent> getConsentByIdAndVersion(String tenantId, String consentId, Integer version)
            throws Exception {
        validateBasicInputs(consentId, tenantId, "Consent ID");

        if (version == null || version <= 0) {
            throw new ConsentException(ErrorCodes.VERSION_NUMBER_INVALID,
                    ErrorCodes.getDescription(ErrorCodes.VERSION_NUMBER_INVALID),
                    "Version must be positive, got: " + version);
        }

        return consentRepositoryImpl.findByConsentIdAndVersion(consentId, version, tenantId);
    }

    public ConsentTokenValidateResponse validateConsentToken(String token) throws Exception {
        return tokenUtility.verifyConsentToken(token);
    }

    // ============================================
    // VALIDATION & PROCESSING METHODS
    // ============================================

    /**
     * ✅ CORE METHOD: Validates purposes exist in template
     * - Checks all user-provided purposes exist in template
     * - Checks mandatory preferences are provided (for creation only)
     */
    private void validatePurposes(List<Preference> templatePreferences,
                                  Map<Purpose, PreferenceStatus> userChoices,
                                  boolean checkMandatory) throws ConsentException {

        // Step 1: Validate all user purposes exist in template
        Set<Purpose> templatePurposes = templatePreferences.stream()
                .map(Preference::getPurpose)
                .collect(Collectors.toSet());

        Set<Purpose> invalidPurposes = userChoices.keySet().stream()
                .filter(purpose -> !templatePurposes.contains(purpose))
                .collect(Collectors.toSet());

        if (!invalidPurposes.isEmpty()) {
            log.error("Invalid purposes provided: {}. Template has: {}", invalidPurposes, templatePurposes);
            throw new ConsentException(
                    ErrorCodes.VALIDATION_ERROR,
                    ErrorCodes.getDescription(ErrorCodes.VALIDATION_ERROR),
                    "Invalid purposes: " + invalidPurposes + ". These do not exist in template."
            );
        }

        // Step 2: Check mandatory preferences if required (for creation)
        if (checkMandatory) {
            Set<Purpose> mandatoryPurposes = templatePreferences.stream()
                    .filter(p -> Boolean.TRUE.equals(p.getIsMandatory()))
                    .map(Preference::getPurpose)
                    .collect(Collectors.toSet());

            Set<Purpose> missingMandatory = mandatoryPurposes.stream()
                    .filter(purpose -> !userChoices.containsKey(purpose))
                    .collect(Collectors.toSet());

            if (!missingMandatory.isEmpty()) {
                log.error("Missing mandatory preferences: {}", missingMandatory);
                throw new ConsentException(
                        ErrorCodes.MISSING_MANDATORY_PREFERENCE,
                        ErrorCodes.getDescription(ErrorCodes.MISSING_MANDATORY_PREFERENCE),
                        "Mandatory preferences must be provided: " + missingMandatory
                );
            }
        }
    }

    /**
     * ✅ Process preferences for CREATION
     * Returns ONLY the preferences user provided (after validation)
     */
    private List<Preference> processPreferencesForCreation(
            List<Preference> templatePreferences,
            Map<Purpose, PreferenceStatus> userChoices) {

        LocalDateTime now = LocalDateTime.now();
        List<Preference> processed = new ArrayList<>();

        for (Preference pref : templatePreferences) {
            if (userChoices.containsKey(pref.getPurpose())) {
                pref.setPreferenceStatus(userChoices.get(pref.getPurpose()));
                pref.setStartDate(now);
                pref.setEndDate(calculatePreferenceEndDate(now, pref.getPreferenceValidity()));
                processed.add(pref);
            }
        }

        return processed;
    }

    /**
     * ✅ Process preferences for UPDATE
     * Returns ALL template preferences, updating only the ones user provided
     */
    private List<Preference> processPreferencesForUpdate(
            List<Preference> templatePreferences,
            Map<Purpose, PreferenceStatus> userChoices) {

        LocalDateTime now = LocalDateTime.now();

        return templatePreferences.stream()
                .peek(pref -> {
                    if (userChoices.containsKey(pref.getPurpose())) {
                        pref.setPreferenceStatus(userChoices.get(pref.getPurpose()));
                        pref.setStartDate(now);
                        pref.setEndDate(calculatePreferenceEndDate(now, pref.getPreferenceValidity()));
                    }
                })
                .collect(Collectors.toList());
    }

    private ConsentHandle validateConsentHandle(String consentHandleId, String tenantId) throws Exception {
        ConsentHandle handle = consentHandleRepository.getByConsentHandleId(consentHandleId, tenantId);

        if (handle == null) {
            throw new ConsentException(ErrorCodes.CONSENT_HANDLE_NOT_FOUND,
                    ErrorCodes.getDescription(ErrorCodes.CONSENT_HANDLE_NOT_FOUND),
                    "Consent handle not found: " + consentHandleId);
        }

        if (handle.getStatus() == ConsentHandleStatus.USED) {
            throw new ConsentException(ErrorCodes.CONSENT_HANDLE_ALREADY_USED,
                    ErrorCodes.getDescription(ErrorCodes.CONSENT_HANDLE_ALREADY_USED),
                    "Consent handle already used: " + consentHandleId);
        }

        if (handle.isExpired()) {
            throw new ConsentException(ErrorCodes.CONSENT_HANDLE_EXPIRED,
                    ErrorCodes.getDescription(ErrorCodes.CONSENT_HANDLE_EXPIRED),
                    "Consent handle expired: " + consentHandleId);
        }

        return handle;
    }

    private void validateUpdateInputs(String consentId, UpdateConsentRequest request, String tenantId)
            throws ConsentException {
        validateBasicInputs(consentId, tenantId, "Consent ID");

        if (request == null || !request.hasUpdates()) {
            throw new ConsentException(ErrorCodes.VALIDATION_ERROR,
                    ErrorCodes.getDescription(ErrorCodes.VALIDATION_ERROR),
                    "Update request must contain at least one update");
        }

        if (request.getConsentHandleId() == null || request.getConsentHandleId().trim().isEmpty()) {
            throw new ConsentException(ErrorCodes.VALIDATION_ERROR,
                    ErrorCodes.getDescription(ErrorCodes.VALIDATION_ERROR),
                    "Consent handle ID required for update");
        }
    }

    private void validateConsentCanBeUpdated(Consent consent, ConsentHandle handle) throws ConsentException {
        if (!consent.getCustomerIdentifiers().getValue().equals(handle.getCustomerIdentifiers().getValue())) {
            throw new ConsentException(ErrorCodes.CONSENT_HANDLE_CUSTOMER_MISMATCH,
                    ErrorCodes.getDescription(ErrorCodes.CONSENT_HANDLE_CUSTOMER_MISMATCH),
                    "Customer mismatch between consent and handle");
        }

        if (!consent.getBusinessId().equals(handle.getBusinessId())) {
            throw new ConsentException(ErrorCodes.CONSENT_HANDLE_BUSINESS_MISMATCH,
                    ErrorCodes.getDescription(ErrorCodes.CONSENT_HANDLE_BUSINESS_MISMATCH),
                    "Business mismatch between consent and handle");
        }

        if (consent.getConsentStatus() != VersionStatus.ACTIVE) {
            throw new ConsentException(ErrorCodes.BUSINESS_RULE_VIOLATION,
                    ErrorCodes.getDescription(ErrorCodes.BUSINESS_RULE_VIOLATION),
                    "Only active consents can be updated");
        }

        if (consent.getStatus() == Status.EXPIRED) {
            throw new ConsentException(ErrorCodes.CONSENT_CANNOT_UPDATE_EXPIRED,
                    ErrorCodes.getDescription(ErrorCodes.CONSENT_CANNOT_UPDATE_EXPIRED),
                    "Cannot update expired consent");
        }
    }

    private void validateBasicInputs(String id, String tenantId, String idName) throws ConsentException {
        if (tenantId == null || tenantId.trim().isEmpty()) {
            throw new ConsentException(ErrorCodes.VALIDATION_ERROR,
                    ErrorCodes.getDescription(ErrorCodes.VALIDATION_ERROR),
                    "Tenant ID cannot be null or empty");
        }

        if (id == null || id.trim().isEmpty()) {
            throw new ConsentException(ErrorCodes.VALIDATION_ERROR,
                    ErrorCodes.getDescription(ErrorCodes.VALIDATION_ERROR),
                    idName + " cannot be null or empty");
        }
    }

    // ============================================
    // HELPER METHODS
    // ============================================

    private ConsentTemplate getTemplate(ConsentHandle handle, String tenantId) throws Exception {
        Optional<ConsentTemplate> templateOpt = templateService.getTemplateByIdAndVersion(
                tenantId, handle.getTemplateId(), handle.getTemplateVersion());

        if (templateOpt.isEmpty()) {
            throw new ConsentException(ErrorCodes.TEMPLATE_NOT_FOUND,
                    ErrorCodes.getDescription(ErrorCodes.TEMPLATE_NOT_FOUND),
                    "Template not found: " + handle.getTemplateId());
        }

        return templateOpt.get();
    }

    private Consent findActiveConsentOrThrow(String consentId, String tenantId) throws ConsentException {
        Consent consent = consentRepositoryImpl.findActiveByConsentId(consentId, tenantId);

        if (consent == null) {
            throw new ConsentException(ErrorCodes.CONSENT_NOT_FOUND,
                    ErrorCodes.getDescription(ErrorCodes.CONSENT_NOT_FOUND),
                    "Active consent not found: " + consentId);
        }

        return consent;
    }

    private Consent buildNewConsent(ConsentHandle handle, ConsentTemplate template,
                                    List<Preference> preferences, String languagePreference) {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime expiry = calculateConsentExpiry(preferences);
        Status status = determineConsentStatus(preferences);

        return Consent.builder()
                .consentId(UUID.randomUUID().toString())
                .consentHandleId(handle.getConsentHandleId())
                .businessId(handle.getBusinessId())
                .templateId(handle.getTemplateId())
                .templateVersion(handle.getTemplateVersion())
                .languagePreferences(languagePreference)
                .multilingual(template.getMultilingual())
                .customerIdentifiers(handle.getCustomerIdentifiers())
                .preferences(preferences)
                .status(status)
                .consentStatus(VersionStatus.ACTIVE)
                .version(1)
                .startDate(now)
                .endDate(expiry)
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .className("com.example.scanner.entity.Consent")
                .build();
    }

    private Consent createNewConsentVersion(Consent existing, UpdateConsentRequest request,
                                            ConsentHandle handle, ConsentTemplate template) throws ConsentException {

        Consent newVersion = Consent.createNewVersionFrom(existing, request,
                handle.getConsentHandleId(), handle.getTemplateVersion());

        if (request.hasPreferenceUpdates()) {
            // Validate purposes exist in template (no mandatory check for updates)
            validatePurposes(template.getPreferences(), request.getPreferencesStatus(), false);

            // Process preferences (returns ALL template preferences for updates)
            List<Preference> updated = processPreferencesForUpdate(
                    template.getPreferences(),
                    request.getPreferencesStatus()
            );

            newVersion.setPreferences(updated);
            newVersion.setEndDate(calculateConsentExpiry(updated));
            newVersion.setStatus(determineConsentStatus(updated));
        }

        return newVersion;
    }

    @Transactional
    private UpdateConsentResponse saveConsentUpdate(Consent active, Consent newVersion,
                                                    ConsentHandle handle, String tenantId) throws Exception {
        String token = generateConsentToken(newVersion);
        newVersion.setConsentJwtToken(token);

        consentRepositoryImpl.save(newVersion, tenantId);
        log.info("Created consent version {} for {}", newVersion.getVersion(), newVersion.getConsentId());

        active.setConsentStatus(VersionStatus.UPDATED);
        active.setUpdatedAt(Instant.now());
        consentRepositoryImpl.save(active, tenantId);

        handle.setStatus(ConsentHandleStatus.USED);
        consentHandleRepository.save(handle, tenantId);

        return UpdateConsentResponse.success(
                newVersion.getConsentId(), newVersion.getId(), newVersion.getVersion(),
                active.getVersion(), token, newVersion.getEndDate()
        );
    }

    /**
     * Dashboard API: Fetch consents with filters
     * Validates all inputs and enriches response with scan and template data
     */
    public List<DashboardResponse> getDashboardData(String tenantId, DashboardRequest request)
            throws ConsentException {

        log.info("Processing dashboard request for tenant: {}, templateID: {}", tenantId, request.getTemplateID());

        TenantContext.setCurrentTenant(tenantId);
        MongoTemplate mongoTemplate = mongoConfig.getMongoTemplateForTenant(tenantId);

        try {
            // Build query with filters
            Criteria criteria = Criteria.where("templateId").is(request.getTemplateID())
                    .and("consentStatus").is(VersionStatus.ACTIVE); // Only fetch active versions

            // Optional: Filter by template version
            if (request.getVersion() != null) {
                criteria.and("templateVersion").is(request.getVersion());
            }

            // Optional: Filter by start date
            if (request.getStartDate() != null) {
                criteria.and("startDate").gte(request.getStartDate());
            }

            // Optional: Filter by end date
            if (request.getEndDate() != null) {
                criteria.and("endDate").lte(request.getEndDate());
            }

            // Optional: Filter by status
            if (request.getStatus() != null) {
                criteria.and("status").is(request.getStatus());
            }

            Query query = new Query(criteria);
            query.with(Sort.by(Sort.Direction.DESC, "createdAt")); // Latest first

            List<Consent> consents = mongoTemplate.find(query, Consent.class);

            if (consents.isEmpty()) {
                log.info("No consents found for templateID: {} with given filters", request.getTemplateID());
                return List.of();
            }

            log.info("Found {} consents for templateID: {}", consents.size(), request.getTemplateID());

            // Map to response DTOs with enriched data
            List<DashboardResponse> responses = consents.stream()
                    .map(consent -> {
                        try {
                            return mapToResponse(consent, tenantId, request.getScanID(), mongoTemplate);
                        } catch (Exception e) {
                            log.error("Error mapping consent {} to response: {}", consent.getConsentId(), e.getMessage());
                            return null;
                        }
                    })
                    .filter(response -> response != null) // Remove null responses
                    .toList();

            log.info("Successfully mapped {} consent records for dashboard", responses.size());
            return responses;

        } catch (Exception e) {
            log.error("Unexpected error fetching dashboard data for tenant: {}, templateID: {}",
                    tenantId, request.getTemplateID(), e);
            throw new ConsentException(
                    ErrorCodes.INTERNAL_ERROR,
                    ErrorCodes.getDescription(ErrorCodes.INTERNAL_ERROR),
                    "Failed to fetch dashboard data: " + e.getMessage()
            );
        } finally {
            TenantContext.clear();
        }
    }

    /**
     * Map Consent to DashboardResponse with enriched data from Template, Handle, and Scan
     */
    private DashboardResponse mapToResponse(Consent consent, String tenantId, String scanIDFilter,
                                            MongoTemplate mongoTemplate) {

        try {
            // Get ConsentHandle
            Query handleQuery = new Query(Criteria.where("consentHandleId").is(consent.getConsentHandleId()));
            ConsentHandle handle = mongoTemplate.findOne(handleQuery, ConsentHandle.class);

            if (handle == null) {
                log.warn("ConsentHandle not found for consentHandleId: {}", consent.getConsentHandleId());
            }

            // Get Template to fetch scanId
            Query templateQuery = new Query(Criteria.where("templateId").is(consent.getTemplateId())
                    .and("version").is(consent.getTemplateVersion()));
            ConsentTemplate template = mongoTemplate.findOne(templateQuery, ConsentTemplate.class);

            String scanId = template != null ? template.getScanId() : null;

            // Apply scanID filter if provided
            if (scanIDFilter != null && !scanIDFilter.trim().isEmpty()) {
                if (scanId == null || !scanId.equals(scanIDFilter)) {
                    return null; // Skip this consent
                }
            }

            // Get Scan Result for URL and subdomains
            String scannedSite = null;
            List<String> subDomains = null;

            if (scanId != null) {
                Query scanQuery = new Query(Criteria.where("transactionId").is(scanId));
                ScanResultEntity scanResult = mongoTemplate.findOne(scanQuery, ScanResultEntity.class);

                if (scanResult != null) {
                    scannedSite = scanResult.getUrl();

                    // Extract subdomains (exclude 'main')
                    if (scanResult.getCookiesBySubdomain() != null && !scanResult.getCookiesBySubdomain().isEmpty()) {
                        subDomains = scanResult.getCookiesBySubdomain().keySet().stream()
                                .filter(subdomain -> !"main".equalsIgnoreCase(subdomain))
                                .sorted()
                                .toList();
                    }
                } else {
                    log.warn("ScanResult not found for scanId: {}", scanId);
                }
            }

            // Extract cookie categories from preferences
            List<String> categories = List.of();
            if (consent.getPreferences() != null && !consent.getPreferences().isEmpty()) {
                categories = consent.getPreferences().stream()
                        .filter(pref -> pref.getPurpose() != null)
                        .map(pref -> pref.getPurpose().toString())
                        .distinct()
                        .sorted()
                        .toList();
            }

            return DashboardResponse.builder()
                    .consentID(consent.getConsentId())
                    .consentHandle(consent.getConsentHandleId())
                    .startDate(consent.getStartDate())
                    .endDate(consent.getEndDate())
                    .cookieCategory(categories)
                    .scannedSite(scannedSite)
                    .subDomain(subDomains != null ? subDomains : List.of())
                    .status(consent.getStatus())
                    .version(consent.getTemplateVersion())
                    .build();

        } catch (Exception e) {
            log.error("Error enriching consent data for consentId: {}", consent.getConsentId(), e);
            throw new RuntimeException("Failed to enrich consent data: " + e.getMessage());
        }
    }

    private LocalDateTime calculatePreferenceEndDate(LocalDateTime start,
                                                     com.example.scanner.dto.Duration validity) {
        if (validity == null) {
            return start.plusYears(1); // Default
        }

        return switch (validity.getUnit()) {
            case YEARS -> start.plusYears(validity.getValue());
            case MONTHS -> start.plusMonths(validity.getValue());
            case DAYS -> start.plusDays(validity.getValue());
        };
    }

    private LocalDateTime calculateConsentExpiry(List<Preference> preferences) {
        return preferences.stream()
                .map(Preference::getEndDate)
                .filter(Objects::nonNull)
                .max(LocalDateTime::compareTo)
                .orElse(LocalDateTime.now().plusYears(1));
    }

    private Status determineConsentStatus(List<Preference> preferences) {
        boolean hasAccept = preferences.stream()
                .anyMatch(p -> p.getPreferenceStatus() == PreferenceStatus.ACCEPTED);

        boolean allExpired = preferences.stream()
                .allMatch(p -> p.getPreferenceStatus() == PreferenceStatus.EXPIRED);

        if (allExpired) return Status.EXPIRED;
        if (hasAccept) return Status.ACTIVE;

        return Status.INACTIVE;
    }

    private String generateConsentToken(Consent consent) throws Exception {
        Gson gson = new GsonBuilder()
                .registerTypeAdapter(LocalDateTime.class, new LocalDateTypeAdapter())
                .registerTypeAdapter(Instant.class, new InstantTypeAdapter())
                .create();

        String json = gson.toJson(consent);
        Date expiry = Date.from(consent.getEndDate().atZone(ZoneId.systemDefault()).toInstant());

        return tokenUtility.generateToken(json, expiry);
    }
}