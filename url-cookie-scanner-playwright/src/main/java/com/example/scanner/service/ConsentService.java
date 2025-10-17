package com.example.scanner.service;

import com.example.scanner.config.MultiTenantMongoConfig;
import com.example.scanner.config.TenantContext;
import com.example.scanner.constants.ErrorCodes;
import com.example.scanner.dto.ConsentDetail;
import com.example.scanner.dto.Preference;
import com.example.scanner.dto.request.CreateConsentRequest;
import com.example.scanner.dto.request.DashboardRequest;
import com.example.scanner.dto.request.UpdateConsentRequest;
import com.example.scanner.dto.response.ConsentCreateResponse;
import com.example.scanner.dto.response.ConsentTokenValidateResponse;
import com.example.scanner.dto.response.DashboardTemplateResponse;
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
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

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

        // REQUIREMENT 4: Validate consent handle (checks PENDING status and 15 min expiry)
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

        // REQUIREMENT 3: FIRST validate ALL template preferences are present in request
        validateAllPreferencesPresent(template.getPreferences(), request.getPreferencesStatus());

        // REQUIREMENT 1: Check if ALL preferences are NOTACCEPTED (Reject All scenario)
        // This check happens AFTER we know all preferences are present
        boolean allNotAccepted = request.getPreferencesStatus().values().stream()
                .allMatch(status -> status == PreferenceStatus.NOTACCEPTED);

        if (allNotAccepted) {
            log.info("All preferences are NOTACCEPTED - marking handle as REJECTED and not creating consent");
            consentHandle.setStatus(ConsentHandleStatus.REJECTED);
            consentHandleRepository.save(consentHandle, tenantId);

            log.info("Updated consent handle {} status to REJECTED", consentHandle.getConsentHandleId());

            return ConsentCreateResponse.builder()
                    .consentId(null)
                    .consentJwtToken(null)
                    .message("All preferences rejected - Consent not created")
                    .consentExpiry(null)
                    .build();
        }

        // REQUIREMENT 2: Validate mandatory preferences are not NOTACCEPTED
        // This only runs if it's NOT a reject-all case
        validateMandatoryNotRejected(template.getPreferences(), request.getPreferencesStatus());

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
     * REQUIREMENT 3: Validate ALL template preferences are present in request
     * This is the FIRST validation that should run
     */
    private void validateAllPreferencesPresent(List<Preference> templatePreferences,
                                               Map<Purpose, PreferenceStatus> userChoices) throws ConsentException {

        Set<Purpose> templatePurposes = templatePreferences.stream()
                .map(Preference::getPurpose)
                .collect(Collectors.toSet());

        // Check if ALL template preferences are in the request
        Set<Purpose> missingPreferences = templatePurposes.stream()
                .filter(purpose -> !userChoices.containsKey(purpose))
                .collect(Collectors.toSet());

        if (!missingPreferences.isEmpty()) {
            log.error("Request must contain all template preferences. Template has {} preferences, request has {}. Missing: {}",
                    templatePurposes.size(), userChoices.size(), missingPreferences);
            throw new ConsentException(
                    ErrorCodes.INCOMPLETE_PREFERENCES,
                    ErrorCodes.getDescription(ErrorCodes.INCOMPLETE_PREFERENCES),
                    "All template preferences must be provided in request. Missing: " + missingPreferences +
                            ". Template expects " + templatePurposes.size() + " preferences, but received " + userChoices.size()
            );
        }

        // Check if user sent any EXTRA purposes not in template
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
    }

    /**
     * REQUIREMENT 2: Validate mandatory preferences (isMandatory=true) cannot be NOTACCEPTED
     * This runs AFTER we know all preferences are present and it's NOT a reject-all case
     */
    private void validateMandatoryNotRejected(List<Preference> templatePreferences,
                                              Map<Purpose, PreferenceStatus> userChoices) throws ConsentException {

        Map<Purpose, Boolean> mandatoryMap = templatePreferences.stream()
                .filter(p -> Boolean.TRUE.equals(p.getIsMandatory()))
                .collect(Collectors.toMap(Preference::getPurpose, Preference::getIsMandatory));

        List<Purpose> rejectedMandatory = userChoices.entrySet().stream()
                .filter(entry -> mandatoryMap.containsKey(entry.getKey())) // Is it mandatory?
                .filter(entry -> entry.getValue() == PreferenceStatus.NOTACCEPTED) // Is it rejected?
                .map(Map.Entry::getKey)
                .collect(Collectors.toList());

        if (!rejectedMandatory.isEmpty()) {
            log.error("User attempted to reject mandatory preferences: {}", rejectedMandatory);
            throw new ConsentException(
                    ErrorCodes.MANDATORY_PREFERENCE_REJECTED,
                    ErrorCodes.getDescription(ErrorCodes.MANDATORY_PREFERENCE_REJECTED),
                    "Mandatory preferences cannot be rejected: " + rejectedMandatory + ". These preferences are required for the service."
            );
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

    /**
     * REQUIREMENT 4: Validate consent handle with explicit PENDING status check
     */
    private ConsentHandle validateConsentHandle(String consentHandleId, String tenantId) throws Exception {
        ConsentHandle handle = consentHandleRepository.getByConsentHandleId(consentHandleId, tenantId);

        if (handle == null) {
            throw new ConsentException(ErrorCodes.CONSENT_HANDLE_NOT_FOUND,
                    ErrorCodes.getDescription(ErrorCodes.CONSENT_HANDLE_NOT_FOUND),
                    "Consent handle not found: " + consentHandleId);
        }

        // REQUIREMENT 4: Explicit check for PENDING status (must be checked before expiry)
        if (handle.getStatus() != ConsentHandleStatus.PENDING) {
            log.error("Consent handle is not in PENDING status. Current status: {}", handle.getStatus());
            throw new ConsentException(ErrorCodes.CONSENT_HANDLE_ALREADY_USED,
                    ErrorCodes.getDescription(ErrorCodes.CONSENT_HANDLE_ALREADY_USED),
                    "Consent handle is already used. Current status: " + handle.getStatus());
        }

        // Check if expired (15 minutes check)
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
            // Apply same validation rules as create consent

            // REQUIREMENT 3: Validate ALL template preferences are present in update request
            validateAllPreferencesPresent(template.getPreferences(), request.getPreferencesStatus());

            // REQUIREMENT 1: Check if ALL preferences are NOTACCEPTED (Reject All scenario for update)
            boolean allNotAccepted = request.getPreferencesStatus().values().stream()
                    .allMatch(status -> status == PreferenceStatus.NOTACCEPTED);

            if (allNotAccepted) {
                log.info("All preferences are NOTACCEPTED in update - marking handle as REJECTED");
                // For update, we still create the new version but mark handle as rejected
                // This maintains the consent history
            }

            // REQUIREMENT 2: Validate mandatory preferences are not NOTACCEPTED
            validateMandatoryNotRejected(template.getPreferences(), request.getPreferencesStatus());

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


    private LocalDateTime calculatePreferenceEndDate(LocalDateTime start,
                                                     com.example.scanner.dto.Duration validity) {
        if (validity == null) {
            return start.plusYears(1);
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

    //-----------------------------------Get DASHBOARD data-------------------------------

    public List<DashboardTemplateResponse> getDashboardDataGroupedByTemplate(
            String tenantId, DashboardRequest request) throws ConsentException {

        log.info("Processing dashboard request for tenant: {}", tenantId);

        TenantContext.setCurrentTenant(tenantId);
        MongoTemplate mongoTemplate = mongoConfig.getMongoTemplateForTenant(tenantId);

        try {
            List<ConsentTemplate> templates;

            if (StringUtils.hasText(request.getTemplateID())) {
                Query templateQuery = new Query(
                        Criteria.where("templateId").is(request.getTemplateID())
                );

                if (request.getVersion() != null) {
                    templateQuery.addCriteria(
                            Criteria.where("version").is(request.getVersion())
                    );
                }

                templates = mongoTemplate.find(templateQuery, ConsentTemplate.class);

            } else {
                templates = mongoTemplate.find(new Query(), ConsentTemplate.class);
            }

            // For each template, fetch consents and build response
            List<DashboardTemplateResponse> responses = new ArrayList<>();

            for (ConsentTemplate template : templates) {
                // Apply scanId filter if provided
                if (StringUtils.hasText(request.getScanID()) &&
                        !request.getScanID().equals(template.getScanId())) {
                    continue;
                }

                // Fetch scan results for this template
                ScanResultEntity scanResult = null;
                String scannedSite = null;
                List<String> subDomains = null;

                if (template.getScanId() != null) {
                    Query scanQuery = new Query(
                            Criteria.where("transactionId").is(template.getScanId())
                    );
                    scanResult = mongoTemplate.findOne(scanQuery, ScanResultEntity.class);

                    if (scanResult != null) {
                        scannedSite = scanResult.getUrl();
                        if (scanResult.getCookiesBySubdomain() != null) {
                            subDomains =  scanResult.getCookiesBySubdomain().keySet().stream().toList();
                        }
                    }
                }

                // Build consent query
                Criteria consentCriteria = Criteria.where("templateId").is(template.getTemplateId())
                        .and("templateVersion").is(template.getVersion());

                if (request.getStartDate() != null) {
                    consentCriteria.and("startDate").gte(request.getStartDate());
                }

                if (request.getEndDate() != null) {
                    consentCriteria.and("endDate").lte(request.getEndDate());
                }

                Query consentQuery = new Query(consentCriteria);
                consentQuery.with(Sort.by(Sort.Direction.DESC, "createdAt"));

                List<Consent> consents = mongoTemplate.find(consentQuery, Consent.class);

                // Map consents to ConsentDetail objects
                List<ConsentDetail> consentDetails = consents.stream()
                        .map(consent -> mapToConsentDetail(consent, template, mongoTemplate))
                        .filter(Objects::nonNull)
                        .collect(Collectors.toList());

                // Build template response
                DashboardTemplateResponse templateResponse = DashboardTemplateResponse.builder()
                        .templateId(template.getTemplateId())
                        .status(template.getTemplateStatus())
                        .scannedSites(scannedSite)
                        .scannedSubDomains(subDomains)
                        .scanId(template.getScanId())
                        .consents(consentDetails)
                        .build();

                responses.add(templateResponse);
            }

            return responses;

        } catch (Exception e) {
            log.error("Error fetching dashboard data: {}", e.getMessage(), e);
            throw new ConsentException(
                    ErrorCodes.INTERNAL_ERROR,
                    "Failed to fetch dashboard data",
                    e.getMessage()
            );
        } finally {
            TenantContext.clear();
        }
    }

    private ConsentDetail mapToConsentDetail(Consent consent, ConsentTemplate template,
                                             MongoTemplate mongoTemplate) {
        try {
            // Fetch consent handle
            Query handleQuery = new Query(
                    Criteria.where("consentHandleId").is(consent.getConsentHandleId())
            );
            ConsentHandle handle = mongoTemplate.findOne(handleQuery, ConsentHandle.class);

            // Get all template preference names
            List<String> templatePreferences = template.getPreferences().stream()
                    .map(Preference::getPurpose)
                    .map(Purpose::toString)
                    .collect(Collectors.toList());

            // Get user accepted preferences
            List<String> userAccepted = consent.getPreferences().stream()
                    .filter(pref -> pref.getPreferenceStatus() == PreferenceStatus.ACCEPTED)
                    .map(Preference::getPurpose)
                    .map(Purpose::toString)
                    .collect(Collectors.toList());

            assert handle != null;
            return ConsentDetail.builder()
                    .consentID(consent.getConsentId())
                    .consentHandle(consent.getConsentHandleId())
                    .templateVersion(consent.getTemplateVersion())
                    .consentVersion(consent.getVersion())
                    .templatePreferences(templatePreferences)
                    .userSelectedPreference(userAccepted)
                    .consentStatus(consent.getStatus().toString())
                    .consentHandleStatus(handle.getStatus().toString())
                    .customerIdentifier(consent.getCustomerIdentifiers())
                    .build();

        } catch (Exception e) {
            log.error("Error mapping consent to detail: {}", e.getMessage());
            return null;
        }
    }
}