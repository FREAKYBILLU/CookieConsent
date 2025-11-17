package com.example.scanner.service;

import com.example.scanner.config.MultiTenantMongoConfig;
import com.example.scanner.config.TenantContext;
import com.example.scanner.constants.ErrorCodes;
import com.example.scanner.dto.ConsentDetail;
import com.example.scanner.dto.Preference;
import com.example.scanner.dto.request.CreateConsentRequest;
import com.example.scanner.dto.request.DashboardRequest;
import com.example.scanner.dto.request.UpdateConsentRequest;
import com.example.scanner.dto.response.*;
import com.example.scanner.entity.CookieConsent;
import com.example.scanner.entity.CookieConsentHandle;
import com.example.scanner.entity.ConsentTemplate;
import com.example.scanner.entity.ScanResultEntity;
import com.example.scanner.enums.*;
import com.example.scanner.exception.ConsentException;
import com.example.scanner.repository.ConsentHandleRepository;
import com.example.scanner.repository.impl.ConsentRepositoryImpl;
import com.example.scanner.util.ConsentUtil;
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
    private final AuditService auditService;


    // ============================================
    // PUBLIC API METHODS
    // ============================================

    public ConsentCreateResponse createConsentByConsentHandleId(CreateConsentRequest request, String tenantId) throws Exception {
        log.info("Processing consent creation for handle: {}, tenant: {}", request.getConsentHandleId(), tenantId);

        // ✅ ADD THIS - Log consent creation initiated
        auditService.logConsentCreationInitiated(tenantId, null, request.getConsentHandleId());


        // Validate preferences present
        if (request.getPreferencesStatus() == null || request.getPreferencesStatus().isEmpty()) {
            throw new ConsentException(ErrorCodes.VALIDATION_ERROR,
                    ErrorCodes.getDescription(ErrorCodes.VALIDATION_ERROR),
                    "Preferences status cannot be empty");
        }

        // REQUIREMENT 4: Validate consent handle (checks PENDING status and 15 min expiry)
        CookieConsentHandle consentHandle = validateConsentHandle(request.getConsentHandleId(), tenantId);

        // Check for existing consent
        CookieConsent existingConsent = consentRepositoryImpl.existByTemplateIdAndTemplateVersionAndCustomerIdentifiers(
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
        CookieConsent consent = buildNewConsent(consentHandle, template, processedPreferences,
                request.getLanguagePreference());

        String consentToken = generateConsentToken(consent);
        consent.setConsentJwtToken(consentToken);

        consentRepositoryImpl.save(consent, tenantId);

        // ✅ ADD THIS - Log consent created
        auditService.logConsentCreated(tenantId, null, consent.getConsentId());

        // Mark handle as USED
        consentHandle.setStatus(ConsentHandleStatus.USED);
        consentHandle.setUpdatedAt(Instant.now());
        consentHandleRepository.save(consentHandle, tenantId);

        // ✅ ADD THIS - Log handle marked used
        auditService.logConsentHandleMarkedUsed(tenantId, null,consentHandle.getConsentHandleId());

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


        // Log consent update initiated
        auditService.logConsentUpdateInitiated(tenantId, null, consentId);

        // Validate inputs
        validateUpdateInputs(consentId, updateRequest, tenantId);

        CookieConsentHandle consentHandle = consentHandleRepository.getByConsentHandleId(updateRequest.getConsentHandleId(), tenantId);
        if (consentHandle == null) {
            throw new ConsentException(ErrorCodes.CONSENT_HANDLE_NOT_FOUND,
                    ErrorCodes.getDescription(ErrorCodes.CONSENT_HANDLE_NOT_FOUND),
                    "Consent handle not found" );
        }

        CookieConsent activeConsent = findActiveConsentOrThrow(consentId, tenantId);

        if (activeConsent.getStatus() == Status.REVOKED || activeConsent.getStatus() == Status.EXPIRED) {
            throw new ConsentException(
                    ErrorCodes.CONSENT_CANNOT_UPDATE_REVOKED,
                    ErrorCodes.getDescription(ErrorCodes.CONSENT_CANNOT_UPDATE_REVOKED),
                    "Consent is expired or revoked and cannot be updated"
            );
        }

        if (updateRequest.getStatus() != null && updateRequest.getStatus() == Status.REVOKED) {
            log.info("Revoking consent: {}", activeConsent.getConsentId());

            activeConsent.setStatus(Status.REVOKED);
            activeConsent.setUpdatedAt(Instant.now());
            consentRepositoryImpl.save(activeConsent, tenantId);

            auditService.logConsentRevoked(tenantId, activeConsent.getBusinessId(), activeConsent.getConsentId());

            log.info("Successfully revoked consent: {}", activeConsent.getConsentId());

            return UpdateConsentResponse.builder()
                    .message("Consent revoked successfully")
                    .build();

        }

        // Get template
        ConsentTemplate template = getTemplate(consentHandle, tenantId);

        // Create new version
        CookieConsent newVersion = createNewConsentVersion(activeConsent, updateRequest, consentHandle, template);

        // Save and return response
        return saveConsentUpdate(activeConsent, newVersion, consentHandle, tenantId);
    }

    public List<CookieConsent> getConsentHistory(String consentId, String tenantId) throws Exception {
        validateBasicInputs(consentId, tenantId, "Consent ID");

        List<CookieConsent> history = consentRepositoryImpl.findAllVersionsByConsentId(consentId, tenantId);

        if (history.isEmpty()) {
            throw new ConsentException(ErrorCodes.CONSENT_NOT_FOUND,
                    ErrorCodes.getDescription(ErrorCodes.CONSENT_NOT_FOUND),
                    "No consent versions found for consentId: " + consentId);
        }

        log.info("Retrieved {} versions for consent: {}", history.size(), consentId);
        return history;
    }

    public Optional<CookieConsent> getConsentByIdAndVersion(String tenantId, String consentId, Integer version)
            throws Exception {
        validateBasicInputs(consentId, tenantId, "Consent ID");

        if (version == null || version <= 0) {
            throw new ConsentException(ErrorCodes.VERSION_NUMBER_INVALID,
                    ErrorCodes.getDescription(ErrorCodes.VERSION_NUMBER_INVALID),
                    "Version must be positive, got: " + version);
        }

        return consentRepositoryImpl.findByConsentIdAndVersion(consentId, version, tenantId);
    }

    public ConsentTokenValidateResponse validateConsentToken(String token, String tenantId) throws Exception {

        String tokenId = token.substring(0, Math.min(20, token.length()));

        // ✅ Log token verification initiated
        auditService.logTokenVerificationInitiated(tenantId, null, tokenId);

        try {
            ConsentTokenValidateResponse response = tokenUtility.verifyConsentToken(token);

            // ✅ Log token signature verified
            auditService.logTokenSignatureVerified(tenantId, null, tokenId);

            // ✅ Log token validation success
            auditService.logTokenValidationSuccess(tenantId, null, tokenId);

            return response;

        } catch (Exception e) {
            // ✅ Log token validation failed
            auditService.logTokenValidationFailed(tenantId, null, tokenId);
            throw e;
        }
    }
    // ============================================
    // VALIDATION & PROCESSING METHODS
    // ============================================

    /**
     * REQUIREMENT 3: Validate ALL template preferences are present in request
     * This is the FIRST validation that should run
     */
    private void validateAllPreferencesPresent(List<Preference> templatePreferences,
                                               Map<String, PreferenceStatus> userChoices) throws ConsentException {

        Set<String> templatePurposes = templatePreferences.stream()
                .map(Preference::getPurpose)
                .collect(Collectors.toSet());

        Set<String> missingPreferences = templatePurposes.stream()
                .filter(category -> !userChoices.containsKey(category))
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

        Set<String> invalidPurposes = userChoices.keySet().stream()
                .filter(category -> !templatePurposes.contains(category))
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
                                              Map<String, PreferenceStatus> userChoices) throws ConsentException {

        Map<String, Boolean> mandatoryMap = templatePreferences.stream()
                .filter(p -> Boolean.TRUE.equals(p.getIsMandatory()))
                .collect(Collectors.toMap(Preference::getPurpose, Preference::getIsMandatory));

        List<String> rejectedMandatory = userChoices.entrySet().stream()
                .filter(entry -> mandatoryMap.containsKey(entry.getKey()))
                .filter(entry -> entry.getValue() == PreferenceStatus.NOTACCEPTED)
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
            Map<String, PreferenceStatus> userChoices) {

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
            Map<String, PreferenceStatus> userChoices) {

        LocalDateTime now = LocalDateTime.now();

        return templatePreferences.stream()
                .map(templatePref -> {
                    Preference pref = new Preference();
                    pref.setPurpose(templatePref.getPurpose());
                    pref.setIsMandatory(templatePref.getIsMandatory());
                    pref.setPreferenceValidity(templatePref.getPreferenceValidity());

                    // Set user's choice
                    if (userChoices.containsKey(pref.getPurpose())) {
                        pref.setPreferenceStatus(userChoices.get(pref.getPurpose()));
                        pref.setStartDate(now);
                        pref.setEndDate(calculatePreferenceEndDate(now, pref.getPreferenceValidity()));
                    }

                    return pref;
                })
                .collect(Collectors.toList());
    }

    /**
     * REQUIREMENT 4: Validate consent handle with explicit PENDING status check
     */
    private CookieConsentHandle validateConsentHandle(String consentHandleId, String tenantId) throws Exception {
        CookieConsentHandle handle = consentHandleRepository.getByConsentHandleId(consentHandleId, tenantId);

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

    private void validateConsentCanBeUpdated(CookieConsent consent, CookieConsentHandle handle) throws ConsentException {
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

    private ConsentTemplate getTemplate(CookieConsentHandle handle, String tenantId) throws Exception {
        Optional<ConsentTemplate> templateOpt = templateService.getTemplateByIdAndVersion(
                tenantId, handle.getTemplateId(), handle.getTemplateVersion());

        if (templateOpt.isEmpty()) {
            throw new ConsentException(ErrorCodes.TEMPLATE_NOT_FOUND,
                    ErrorCodes.getDescription(ErrorCodes.TEMPLATE_NOT_FOUND),
                    "Template not found: " + handle.getTemplateId());
        }

        return templateOpt.get();
    }

    private CookieConsent findActiveConsentOrThrow(String consentId, String tenantId) throws ConsentException {
        CookieConsent consent = consentRepositoryImpl.findActiveByConsentId(consentId, tenantId);

        if (consent == null) {
            throw new ConsentException(ErrorCodes.CONSENT_NOT_FOUND,
                    ErrorCodes.getDescription(ErrorCodes.CONSENT_NOT_FOUND),
                    "Active consent not found: " + consentId);
        }

        return consent;
    }

    private CookieConsent buildNewConsent(CookieConsentHandle handle, ConsentTemplate template,
                                          List<Preference> preferences, String languagePreference) {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime expiry = calculateConsentExpiry(preferences);
        Status status = determineConsentStatus(preferences);

        return CookieConsent.builder()
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

    private CookieConsent createNewConsentVersion(CookieConsent existing, UpdateConsentRequest request,
                                                  CookieConsentHandle handle, ConsentTemplate template) throws ConsentException {

        CookieConsent newVersion = ConsentUtil.createNewVersionFrom(existing, request,
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
    private UpdateConsentResponse saveConsentUpdate(CookieConsent active, CookieConsent newVersion,
                                                    CookieConsentHandle handle, String tenantId) throws Exception {
        String token = generateConsentToken(newVersion);
        newVersion.setConsentJwtToken(token);

        consentRepositoryImpl.save(newVersion, tenantId);
        log.info("Created consent version {} for {}", newVersion.getVersion(), newVersion.getConsentId());

        // Log new version created
        auditService.logNewConsentVersionCreated(tenantId, null, newVersion.getConsentId());

        active.setConsentStatus(VersionStatus.UPDATED);
        active.setUpdatedAt(Instant.now());
        consentRepositoryImpl.save(active, tenantId);

        // Log old version marked updated
        auditService.logOldConsentVersionMarkedUpdated(tenantId, null, active.getConsentId());

        // ADD THESE 3 LINES - MISSING TTHA YE!
        handle.setStatus(ConsentHandleStatus.USED);
        handle.setUpdatedAt(Instant.now());
        consentHandleRepository.save(handle, tenantId);

        // ADD THIS LINE - YE BHI MISSING THA!
        auditService.logConsentHandleMarkedUsedAfterUpdate(tenantId, null, handle.getConsentHandleId());

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
                .filter(pref -> pref.getPreferenceStatus() == PreferenceStatus.ACCEPTED)
                .map(Preference::getEndDate)
                .filter(Objects::nonNull)
                .min(LocalDateTime::compareTo)
                .orElse(LocalDateTime.now().plusYears(1));
    }

    private Status determineConsentStatus(List<Preference> preferences) {
        // Check if all preferences have expired
        boolean allExpired = preferences.stream()
                .allMatch(p -> p.getPreferenceStatus() == PreferenceStatus.EXPIRED);

        if (allExpired) {
            return Status.EXPIRED;
        }

        // If consent exists, at least one preference was accepted
        // (All-reject scenario doesn't create consent)
        // Therefore, default status is ACTIVE
        return Status.ACTIVE;
    }

    private String generateConsentToken(CookieConsent consent) throws Exception {
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
                            subDomains = scanResult.getCookiesBySubdomain().keySet().stream().toList();
                        }
                    }
                }

                // ========== STEP 1: Fetch Consents (Existing Logic) ==========
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

                List<CookieConsent> consents = mongoTemplate.find(consentQuery, CookieConsent.class);

                // Map consents to ConsentDetail objects
                List<ConsentDetail> consentDetails = consents.stream()
                        .map(consent -> mapToConsentDetail(consent, template, mongoTemplate))
                        .filter(Objects::nonNull)
                        .collect(Collectors.toList());

                // ========== STEP 2: NEW - Fetch Handles WITHOUT Consents ==========
                // Get all consent handles for this template that are REJECTED, EXPIRED, or PENDING
                Criteria handleCriteria = Criteria.where("templateId").is(template.getTemplateId())
                        .and("templateVersion").is(template.getVersion())
                        .and("status").in(
                                ConsentHandleStatus.REJECTED,
                                ConsentHandleStatus.REQ_EXPIRED,
                                ConsentHandleStatus.PENDING
                        );

                if (request.getStartDate() != null && request.getEndDate() != null) {
                    handleCriteria.and("createdAt")
                            .gte(request.getStartDate().atZone(ZoneId.systemDefault()).toInstant())
                            .lte(request.getEndDate().atZone(ZoneId.systemDefault()).toInstant());
                } else if (request.getStartDate() != null) {
                    handleCriteria.and("createdAt")
                            .gte(request.getStartDate().atZone(ZoneId.systemDefault()).toInstant());
                } else if (request.getEndDate() != null) {
                    handleCriteria.and("createdAt")
                            .lte(request.getEndDate().atZone(ZoneId.systemDefault()).toInstant());
                }

                Query handleQuery = new Query(handleCriteria);
                handleQuery.with(Sort.by(Sort.Direction.DESC, "createdAt"));

                List<CookieConsentHandle> orphanHandles = mongoTemplate.find(handleQuery, CookieConsentHandle.class);

                log.info("Found {} orphan handles (REJECTED/EXPIRED/PENDING) for template: {}",
                        orphanHandles.size(), template.getTemplateId());

                // Map orphan handles to ConsentDetail objects
                List<ConsentDetail> handleDetails = orphanHandles.stream()
                        .map(handle -> mapHandleToConsentDetail(handle, template))
                        .filter(Objects::nonNull)
                        .collect(Collectors.toList());

                // ========== STEP 3: Combine Both Lists ==========
                List<ConsentDetail> allDetails = new ArrayList<>();
                allDetails.addAll(consentDetails);     // Consents with USED handles
                allDetails.addAll(handleDetails);      // Orphan handles (REJECTED/EXPIRED/PENDING)

                // Sort by creation time (most recent first)
                allDetails.sort((a, b) -> {
                    // Get creation time from consent or handle
                    // Since we don't have createdAt in ConsentDetail, sort by consentVersion desc
                    // Handles will have null version, so they'll come after
                    if (a.getConsentVersion() == null && b.getConsentVersion() == null) return 0;
                    if (a.getConsentVersion() == null) return 1;
                    if (b.getConsentVersion() == null) return -1;
                    return b.getConsentVersion().compareTo(a.getConsentVersion());
                });

                // Build template response
                DashboardTemplateResponse templateResponse = DashboardTemplateResponse.builder()
                        .templateId(template.getTemplateId())
                        .status(template.getTemplateStatus())
                        .scannedSites(scannedSite)
                        .scannedSubDomains(subDomains)
                        .scanId(template.getScanId())
                        .consents(allDetails)  // Contains both consents AND orphan handles
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

    private ConsentDetail mapHandleToConsentDetail(CookieConsentHandle handle, ConsentTemplate template) {
        try {
            // Get all template preference names
            List<String> templatePreferences = template.getPreferences().stream()
                    .map(Preference::getPurpose)
                    .collect(Collectors.toList());

            return ConsentDetail.builder()
                    .consentID(null)  // No consent created
                    .consentHandle(handle.getConsentHandleId())
                    .templateVersion(handle.getTemplateVersion())
                    .consentVersion(null)  // No consent version
                    .templatePreferences(templatePreferences)
                    .userSelectedPreference(Collections.emptyList())  // User didn't select anything OR rejected all
                    .consentStatus(null)  // No consent = no consent status
                    .consentHandleStatus(handle.getStatus().toString())  // REJECTED/EXPIRED/PENDING
                    .customerIdentifier(handle.getCustomerIdentifiers())
                    .build();

        } catch (Exception e) {
            log.error("Error mapping handle to detail: {}", e.getMessage());
            return null;
        }
    }

    private ConsentDetail mapToConsentDetail(CookieConsent consent, ConsentTemplate template,
                                             MongoTemplate mongoTemplate) {
        try {
            // Fetch consent handle
            Query handleQuery = new Query(
                    Criteria.where("consentHandleId").is(consent.getConsentHandleId())
            );
            CookieConsentHandle handle = mongoTemplate.findOne(handleQuery, CookieConsentHandle.class);

            // Get all template preference names
            List<String> templatePreferences = template.getPreferences().stream()
                    .map(Preference::getPurpose)
                    .collect(Collectors.toList());

            List<String> userAccepted = consent.getPreferences().stream()
                    .filter(pref -> pref.getPreferenceStatus() == PreferenceStatus.ACCEPTED)
                    .map(Preference::getPurpose)
                    .collect(Collectors.toList());

            Instant lastUpdated = consent.getUpdatedAt() != null &&
                    consent.getCreatedAt() != null &&
                    consent.getUpdatedAt().isAfter(consent.getCreatedAt())
                    ? consent.getUpdatedAt()
                    : consent.getCreatedAt();

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
                    .lastUpdated(lastUpdated)
                    .build();

        } catch (Exception e) {
            log.error("Error mapping consent to detail: {}", e.getMessage());
            return null;
        }
    }

    public CheckConsentResponse getConsentStatus(String deviceId, String url, String consentId, String tenantId){
        if (deviceId == null || deviceId.trim().isEmpty()) {
            throw new IllegalArgumentException("Device ID is required");
        }
        if (url == null || url.trim().isEmpty()) {
            throw new IllegalArgumentException("URL is required");
        }
        TenantContext.setCurrentTenant(tenantId);

        try {
            MongoTemplate mongoTemplate = mongoConfig.getMongoTemplateForTenant(TenantContext.getCurrentTenant());
            if (consentId != null && !consentId.isEmpty()) {
                Query handleQuery = new Query(
                        Criteria.where("consentId").is(consentId)
                ).with(Sort.by(Sort.Direction.DESC, "version")).limit(1);

                CookieConsent consent = mongoTemplate.findOne(handleQuery, CookieConsent.class);

                if (consent == null) {
                    return CheckConsentResponse.builder()
                            .consentStatus("No_Record")
                            .consentHandleId("No_Record")
                            .build();
                }

                return CheckConsentResponse.builder()
                        .consentStatus(consent.getStatus().toString())
                        .consentHandleId(consent.getConsentHandleId())
                        .build();
            }
            // Case 2: DeviceId and URL provided - find consent handle first
            Query handleQuery = new Query();
            handleQuery.addCriteria(Criteria.where("customerIdentifiers.value").is(deviceId));
            handleQuery.addCriteria(Criteria.where("url").is(url));
            handleQuery.with(Sort.by(Sort.Direction.DESC, "createdAt")); // Latest first
            handleQuery.limit(1);

            CookieConsentHandle latestHandle = mongoTemplate.findOne(handleQuery, CookieConsentHandle.class);

            if (latestHandle == null) {
                return CheckConsentResponse.builder()
                        .consentStatus("No_Record")
                        .consentHandleId("No_Record")
                        .build();
            }

            if(latestHandle.getStatus().equals(ConsentHandleStatus.USED)){
                Query consentQuery = new Query(Criteria.where("consentHandleId").is(latestHandle.getConsentHandleId()))
                        .with(Sort.by(Sort.Direction.DESC, "version")).limit(1);;
                CookieConsent consent = mongoTemplate.findOne(consentQuery, CookieConsent.class);

                if (consent != null) {
                    return CheckConsentResponse.builder()
                            .consentStatus(consent.getStatus().toString())
                            .consentHandleId(latestHandle.getConsentHandleId())
                            .build();
                }
            }

                return CheckConsentResponse.builder()
                        .consentStatus(latestHandle.getStatus().toString())
                        .consentHandleId(latestHandle.getConsentHandleId())
                        .build();

        } catch (IllegalArgumentException e) {
            log.error("Invalid input parameters: {}", e.getMessage());
            throw e;
        } catch (Exception e) {
            log.error("Error checking consent status - deviceId: {}, url: {}, consentId: {}",
                    deviceId, url, consentId, e);
            throw new RuntimeException("Failed to retrieve consent status", e);
        }finally {
            TenantContext.clear();
        }
    }
}