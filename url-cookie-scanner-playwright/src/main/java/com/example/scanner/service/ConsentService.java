package com.example.scanner.service;

import com.example.scanner.constants.ErrorCodes;
import com.example.scanner.dto.Preference;
import com.example.scanner.dto.request.CreateConsentRequest;
import com.example.scanner.dto.request.UpdateConsentRequest;
import com.example.scanner.dto.response.ConsentCreateResponse;
import com.example.scanner.dto.response.ConsentTokenValidateResponse;
import com.example.scanner.dto.response.UpdateConsentResponse;
import com.example.scanner.entity.Consent;
import com.example.scanner.entity.ConsentHandle;
import com.example.scanner.entity.ConsentTemplate;
import com.example.scanner.enums.ConsentHandleStatus;
import com.example.scanner.enums.Period;
import com.example.scanner.enums.PreferenceStatus;
import com.example.scanner.enums.Purpose;
import com.example.scanner.enums.Status;
import com.example.scanner.enums.VersionStatus;
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

    // ==== EXISTING CONSENT CREATION METHOD ====

    public ConsentCreateResponse createConsentByConsentHandleId(CreateConsentRequest request, String tenantId) throws Exception {
        log.info("Processing consent creation for handle: {}, tenant: {}", request.getConsentHandleId(), tenantId);

        // ✅ Validate request has preferences
        if (request.getPreferencesStatus() == null || request.getPreferencesStatus().isEmpty()) {
            throw new ConsentException(ErrorCodes.VALIDATION_ERROR,
                    ErrorCodes.getDescription(ErrorCodes.VALIDATION_ERROR),
                    "Preferences status cannot be empty");
        }

        // Validate consent handle exists and is valid
        ConsentHandle currentHandle = this.consentHandleRepository.getByConsentHandleId(request.getConsentHandleId(), tenantId);
        if (ObjectUtils.isEmpty(currentHandle)) {
            log.error("Consent handle not found: {}", request.getConsentHandleId());
            throw new ConsentException(ErrorCodes.CONSENT_HANDLE_NOT_FOUND,
                    ErrorCodes.getDescription(ErrorCodes.CONSENT_HANDLE_NOT_FOUND),
                    "No consent handle found with ID: " + request.getConsentHandleId());
        }

        // Check if consent handle is already used
        if (currentHandle.getStatus().equals(ConsentHandleStatus.USED)) {
            log.error("Consent handle already used: {}", request.getConsentHandleId());
            throw new ConsentException(ErrorCodes.CONSENT_HANDLE_ALREADY_USED,
                    ErrorCodes.getDescription(ErrorCodes.CONSENT_HANDLE_ALREADY_USED),
                    "Cannot reuse consent handle: " + request.getConsentHandleId());
        }

        // Check if consent handle is expired
        if (currentHandle.isExpired()) {
            log.error("Consent handle expired: {}", request.getConsentHandleId());
            throw new ConsentException(ErrorCodes.CONSENT_HANDLE_EXPIRED,
                    ErrorCodes.getDescription(ErrorCodes.CONSENT_HANDLE_EXPIRED),
                    "Consent handle expired on: " + currentHandle.getExpiresAt());
        }

        // Check if consent already exists for this template and customer
        Consent isAlreadyExist = this.consentRepositoryImpl.existByTemplateIdAndTemplateVersionAndCustomerIdentifiers(
                currentHandle.getTemplateId(), currentHandle.getTemplateVersion(), currentHandle.getCustomerIdentifiers(), tenantId);

        if (!ObjectUtils.isEmpty(isAlreadyExist)) {
            log.info("Consent already exists for template: {}, customer: {}",
                    currentHandle.getTemplateId(), currentHandle.getCustomerIdentifiers().getValue());
            return ConsentCreateResponse.builder()
                    .consentId(isAlreadyExist.getConsentId())
                    .consentJwtToken(isAlreadyExist.getConsentJwtToken())
                    .message("Consent already exists!")
                    .consentExpiry(isAlreadyExist.getEndDate())
                    .build();
        }

        // Get template using your existing ConsentTemplateService method
        Optional<ConsentTemplate> templateOpt = this.templateService.getTemplateByTenantAndTemplateId(tenantId, currentHandle.getTemplateId());
        if (templateOpt.isEmpty()) {
            log.error("Template not found for templateId: {}", currentHandle.getTemplateId());
            throw new ConsentException(ErrorCodes.TEMPLATE_NOT_FOUND);
        }
        ConsentTemplate template = templateOpt.get();

        String consentId = UUID.randomUUID().toString();
        LocalDateTime now = LocalDateTime.now();
        final LocalDateTime[] consentExpiryList = {now};
        List<Preference> updatedPreferences = new ArrayList<>();

        for (Preference preference : template.getPreferences()) {
            boolean matched = false;

            // ✅ Direct check - no inner loop needed
            if (request.getPreferencesStatus().containsKey(preference.getPurpose())) {
                // Found a match - apply the status
                preference.setPreferenceStatus(request.getPreferencesStatus().get(preference.getPurpose()));
                preference.setStartDate(now);
                LocalDateTime endDate = now;

                // Calculate end date based on preference validity
                if (preference.getPreferenceValidity() != null) {
                    if (preference.getPreferenceValidity().getUnit().equals(Period.YEARS)) {
                        endDate = endDate.plusYears(preference.getPreferenceValidity().getValue());
                    } else if (preference.getPreferenceValidity().getUnit().equals(Period.MONTHS)) {
                        endDate = endDate.plusMonths(preference.getPreferenceValidity().getValue());
                    } else {
                        endDate = endDate.plusDays(preference.getPreferenceValidity().getValue());
                    }
                } else {
                    // Default to 1 year if no validity specified
                    endDate = endDate.plusYears(1);
                }

                preference.setEndDate(endDate);
                if (endDate.isAfter(consentExpiryList[0])) {
                    consentExpiryList[0] = endDate;
                }
                updatedPreferences.add(preference);
                matched = true;
            }

            // Handle mandatory preferences
            if (!matched && preference.getIsMandatory() != null && preference.getIsMandatory()) {
                log.error("Mandatory preference not provided in request: {}", preference.getPurpose());  // ✅ Changed
                throw new ConsentException(
                        ErrorCodes.MISSING_MANDATORY_PREFERENCE,
                        ErrorCodes.getDescription(ErrorCodes.MISSING_MANDATORY_PREFERENCE),
                        "Mandatory preference must be provided: " + preference.getPurpose()  // ✅ Changed
                );
            }
        }

        LocalDateTime consentExpiry = consentExpiryList[0];

        // Create consent entity (version 1 for initial creation)
        Consent consent = Consent.builder()
                .consentId(consentId)
                .consentHandleId(currentHandle.getConsentHandleId())
                .businessId(currentHandle.getBusinessId())
                .templateId(currentHandle.getTemplateId())
                .templateVersion(currentHandle.getTemplateVersion())
                .languagePreferences(request.getLanguagePreference())
                .multilingual(template.getMultilingual())
                .customerIdentifiers(currentHandle.getCustomerIdentifiers())
                .preferences(updatedPreferences)
                .status(determineConsentStatus(updatedPreferences))
                .consentStatus(VersionStatus.ACTIVE) // First version is always active
                .version(1) // First version
                .startDate(now)
                .endDate(consentExpiry)
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .className("com.example.scanner.entity.Consent")
                .build();

        // Generate JWT token
        String consentToken = generateConsentToken(consent);
        consent.setConsentJwtToken(consentToken);

        // Save consent and update consent handle status
        try {
            this.consentRepositoryImpl.save(consent, tenantId);
            currentHandle.setStatus(ConsentHandleStatus.USED);
            this.consentHandleRepository.save(currentHandle, tenantId);

            log.info("Successfully saved consent: {} and updated handle status", consentId);
        } catch (Exception e) {
            log.error("Error saving consent: {}", e.getMessage());
            throw e;
        }

        ConsentCreateResponse response = ConsentCreateResponse.builder()
                .consentId(consentId)
                .consentJwtToken(consentToken)
                .message("Consent created successfully!")
                .consentExpiry(consentExpiry)
                .build();

        return response;
    }

    // ==== NEW CONSENT UPDATE METHOD (VERSIONING) ====

    /**
     * Update an existing consent by creating a new version
     * This follows the same security model as consent creation - requires valid consent handle
     *
     * @param consentId Logical consent ID (not document ID)
     * @param updateRequest The update request with changes and consent handle
     * @param tenantId The tenant identifier
     * @return UpdateConsentResponse with new version details
     * @throws ConsentException for various validation and business rule violations
     */
    @Transactional
    public UpdateConsentResponse updateConsent(String consentId, UpdateConsentRequest updateRequest, String tenantId)
            throws Exception {

        log.info("Processing consent update for consentId: {}, tenant: {}", consentId, tenantId);

        // STEP 1: Validate inputs
        validateUpdateInputs(consentId, updateRequest, tenantId);

        // STEP 2: Validate consent handle (same security as consent creation)
        ConsentHandle consentHandle = validateConsentHandle(updateRequest.getConsentHandleId(), tenantId);

        // STEP 3: Find current active consent
        Consent activeConsent = findActiveConsentOrThrow(consentId, tenantId);

        // STEP 4: Validate consent can be updated
        validateConsentCanBeUpdated(activeConsent, consentHandle);

        // STEP 5: Get template for processing preferences
        ConsentTemplate template = getTemplateForUpdate(consentHandle, tenantId);

        // STEP 6: Create new consent version
        Consent newVersion = createNewConsentVersion(activeConsent, updateRequest, consentHandle, template);

        // STEP 7: Save new version and update statuses (with rollback on failure)
        return saveConsentUpdate(activeConsent, newVersion, consentHandle, tenantId);
    }

    // ==== CONSENT HISTORY METHODS ====

    /**
     * Get consent history (all versions) for a logical consent ID
     */
    public List<Consent> getConsentHistory(String consentId, String tenantId) throws Exception {
        if (tenantId == null || tenantId.trim().isEmpty()) {
            throw new ConsentException(ErrorCodes.VALIDATION_ERROR,
                    ErrorCodes.getDescription(ErrorCodes.VALIDATION_ERROR),
                    "Tenant ID cannot be null or empty");
        }

        if (consentId == null || consentId.trim().isEmpty()) {
            throw new ConsentException(ErrorCodes.VALIDATION_ERROR,
                    ErrorCodes.getDescription(ErrorCodes.VALIDATION_ERROR),
                    "Consent ID cannot be null or empty");
        }

        List<Consent> history = consentRepositoryImpl.findAllVersionsByConsentId(consentId, tenantId);

        if (history.isEmpty()) {
            log.warn("No consent versions found for consentId: {} in tenant: {}", consentId, tenantId);
            throw new ConsentException(ErrorCodes.CONSENT_NOT_FOUND,
                    ErrorCodes.getDescription(ErrorCodes.CONSENT_NOT_FOUND),
                    "No consent versions found for consentId: " + consentId + " in tenant: " + tenantId);
        }

        log.info("Retrieved {} versions for consent: {} in tenant: {}", history.size(), consentId, tenantId);
        return history;
    }

    /**
     * Get specific version of a consent by logical consent ID and version number
     * Used for historical consent lookups and audit trails
     */
    public Optional<Consent> getConsentByIdAndVersion(String tenantId, String consentId, Integer version)
            throws Exception {

        if (tenantId == null || tenantId.trim().isEmpty()) {
            throw new ConsentException(ErrorCodes.VALIDATION_ERROR,
                    ErrorCodes.getDescription(ErrorCodes.VALIDATION_ERROR),
                    "Tenant ID cannot be null or empty");
        }

        if (consentId == null || consentId.trim().isEmpty()) {
            throw new ConsentException(ErrorCodes.VALIDATION_ERROR,
                    ErrorCodes.getDescription(ErrorCodes.VALIDATION_ERROR),
                    "Consent ID cannot be null or empty");
        }

        if (version == null || version <= 0) {
            throw new ConsentException(ErrorCodes.VERSION_NUMBER_INVALID,
                    ErrorCodes.getDescription(ErrorCodes.VERSION_NUMBER_INVALID),
                    "Version number must be a positive integer, got: " + version);
        }

        try {
            Optional<Consent> consentOpt = consentRepositoryImpl.findByConsentIdAndVersion(consentId, version, tenantId);

            log.info("Retrieved consent version {} for consentId: {} in tenant: {}", version, consentId, tenantId);
            return consentOpt;

        } catch (Exception e) {
            log.error("Error retrieving consent version for consentId: {}, version: {}, tenant: {}",
                    consentId, version, tenantId, e);
            throw new ConsentException(ErrorCodes.INTERNAL_ERROR,
                    ErrorCodes.getDescription(ErrorCodes.INTERNAL_ERROR),
                    "Error retrieving consent version: " + e.getMessage());
        }
    }

    public ConsentTokenValidateResponse validateConsentToken(String token) throws Exception {
        return this.tokenUtility.verifyConsentToken(token);
    }

    /**
     * Validate update request inputs
     */
    private void validateUpdateInputs(String consentId, UpdateConsentRequest updateRequest, String tenantId) throws ConsentException {
        if (tenantId == null || tenantId.trim().isEmpty()) {
            throw new ConsentException(ErrorCodes.VALIDATION_ERROR,
                    ErrorCodes.getDescription(ErrorCodes.VALIDATION_ERROR),
                    "Tenant ID cannot be null or empty");
        }

        if (consentId == null || consentId.trim().isEmpty()) {
            throw new ConsentException(ErrorCodes.VALIDATION_ERROR,
                    ErrorCodes.getDescription(ErrorCodes.VALIDATION_ERROR),
                    "Consent ID cannot be null or empty");
        }

        if (updateRequest == null) {
            throw new ConsentException(ErrorCodes.VALIDATION_ERROR,
                    ErrorCodes.getDescription(ErrorCodes.VALIDATION_ERROR),
                    "Update request cannot be null");
        }

        if (updateRequest.getConsentHandleId() == null || updateRequest.getConsentHandleId().trim().isEmpty()) {
            throw new ConsentException(ErrorCodes.VALIDATION_ERROR,
                    ErrorCodes.getDescription(ErrorCodes.VALIDATION_ERROR),
                    "Consent handle ID cannot be null or empty");
        }

        if (!updateRequest.hasUpdates()) {
            throw new ConsentException(ErrorCodes.VALIDATION_ERROR,
                    ErrorCodes.getDescription(ErrorCodes.VALIDATION_ERROR),
                    "Update request must contain at least one update");
        }
    }

    /**
     * Validate consent handle for update operation
     * Uses same validation as consent creation for security consistency
     */
    private ConsentHandle validateConsentHandle(String consentHandleId, String tenantId) throws Exception {
        ConsentHandle consentHandle = consentHandleRepository.getByConsentHandleId(consentHandleId, tenantId);

        if (consentHandle == null) {
            log.error("Consent handle not found for update: {}", consentHandleId);
            throw new ConsentException(ErrorCodes.CONSENT_HANDLE_NOT_FOUND,
                    ErrorCodes.getDescription(ErrorCodes.CONSENT_HANDLE_NOT_FOUND),
                    "No consent handle found with ID: " + consentHandleId);
        }

        if (consentHandle.getStatus() == ConsentHandleStatus.USED) {
            log.error("Consent handle already used for update: {}", consentHandleId);
            throw new ConsentException(ErrorCodes.CONSENT_HANDLE_ALREADY_USED,
                    ErrorCodes.getDescription(ErrorCodes.CONSENT_HANDLE_ALREADY_USED),
                    "Consent handle " + consentHandleId + " has already been used");
        }

        if (consentHandle.isExpired()) {
            log.error("Consent handle expired for update: {}", consentHandleId);
            throw new ConsentException(ErrorCodes.CONSENT_HANDLE_EXPIRED,
                    ErrorCodes.getDescription(ErrorCodes.CONSENT_HANDLE_EXPIRED),
                    "Consent handle " + consentHandleId + " expired at " + consentHandle.getExpiresAt());
        }

        return consentHandle;
    }

    /**
     * Find active consent or throw exception
     */
    private Consent findActiveConsentOrThrow(String consentId, String tenantId) throws ConsentException {
        Consent activeConsent = consentRepositoryImpl.findActiveByConsentId(consentId, tenantId);

        if (activeConsent == null) {
            log.warn("Active consent not found: {} in tenant: {}", consentId, tenantId);
            throw new ConsentException(ErrorCodes.CONSENT_NOT_FOUND,
                    ErrorCodes.getDescription(ErrorCodes.CONSENT_NOT_FOUND),
                    "No active consent found with ID: " + consentId + " in tenant: " + tenantId);
        }

        return activeConsent;
    }

    /**
     * Validate that consent can be updated
     */
    private void validateConsentCanBeUpdated(Consent consent, ConsentHandle consentHandle) throws ConsentException {
        // Business rule: Consent handle must be for the same customer
        if (!consent.getCustomerIdentifiers().getValue().equals(consentHandle.getCustomerIdentifiers().getValue())) {
            throw new ConsentException(ErrorCodes.CONSENT_HANDLE_CUSTOMER_MISMATCH,
                    ErrorCodes.getDescription(ErrorCodes.CONSENT_HANDLE_CUSTOMER_MISMATCH),
                    "Consent handle customer: " + consentHandle.getCustomerIdentifiers().getValue() +
                            " does not match consent customer: " + consent.getCustomerIdentifiers().getValue());
        }

        // Business rule: Consent handle must be for the same business
        if (!consent.getBusinessId().equals(consentHandle.getBusinessId())) {
            throw new ConsentException(ErrorCodes.CONSENT_HANDLE_BUSINESS_MISMATCH,
                    ErrorCodes.getDescription(ErrorCodes.CONSENT_HANDLE_BUSINESS_MISMATCH),
                    "Consent handle business: " + consentHandle.getBusinessId() +
                            " does not match consent business: " + consent.getBusinessId());
        }

        // Business rule: Only active consents can be updated
        if (consent.getConsentStatus() != VersionStatus.ACTIVE) {
            throw new ConsentException(ErrorCodes.BUSINESS_RULE_VIOLATION,
                    ErrorCodes.getDescription(ErrorCodes.BUSINESS_RULE_VIOLATION),
                    "Only active consents can be updated. Current status: " + consent.getConsentStatus());
        }

        // Business rule: Don't allow updating expired consents
        if (consent.getStatus() == Status.EXPIRED) {
            throw new ConsentException(ErrorCodes.CONSENT_CANNOT_UPDATE_EXPIRED,
                    ErrorCodes.getDescription(ErrorCodes.CONSENT_CANNOT_UPDATE_EXPIRED),
                    "Cannot update expired consent: " + consent.getConsentId());
        }
    }

    /**
     * Get template for processing the update
     */
    private ConsentTemplate getTemplateForUpdate(ConsentHandle consentHandle, String tenantId) throws Exception {
        Optional<ConsentTemplate> templateOpt = templateService.getTemplateByIdAndVersion(
                tenantId, consentHandle.getTemplateId(), consentHandle.getTemplateVersion());

        if (templateOpt.isEmpty()) {
            log.error("Template not found for consent update: templateId={}, version={}",
                    consentHandle.getTemplateId(), consentHandle.getTemplateVersion());
            throw new ConsentException(ErrorCodes.TEMPLATE_NOT_FOUND,
                    ErrorCodes.getDescription(ErrorCodes.TEMPLATE_NOT_FOUND),
                    "Template not found: ID=" + consentHandle.getTemplateId() +
                            ", version=" + consentHandle.getTemplateVersion());
        }

        return templateOpt.get();
    }

    /**
     * Create new consent version with updates applied
     */
    private Consent createNewConsentVersion(Consent existingConsent, UpdateConsentRequest updateRequest,
                                            ConsentHandle consentHandle, ConsentTemplate template) {

        // Create new version using the helper method in Consent entity
        Consent newVersion = Consent.createNewVersionFrom(
                existingConsent,
                updateRequest,
                consentHandle.getConsentHandleId(),
                consentHandle.getTemplateVersion()
        );

        // Process preferences if they were updated
        if (updateRequest.hasPreferenceUpdates()) {
            List<Preference> updatedPreferences = processPreferenceUpdates(
                    template.getPreferences(),
                    updateRequest.getPreferencesStatus()
            );
            newVersion.setPreferences(updatedPreferences);

            // Recalculate end date based on new preferences
            LocalDateTime consentExpiry = calculateConsentExpiry(updatedPreferences);
            newVersion.setEndDate(consentExpiry);

            // Recalculate status based on new preferences
            Status newStatus = determineConsentStatus(updatedPreferences);
            newVersion.setStatus(newStatus);
        }

        return newVersion;
    }

    /**
     * ✅ UPDATED: Process preference updates with user's new choices using Purpose enum
     */
    private List<Preference> processPreferenceUpdates(List<Preference> templatePreferences,
                                                      Map<Purpose, PreferenceStatus> userChoices) {
        LocalDateTime now = LocalDateTime.now();

        return templatePreferences.stream()
                .peek(preference -> {
                    // ✅ Direct check - no loop needed
                    if (userChoices.containsKey(preference.getPurpose())) {
                        // Apply user's new choice
                        preference.setPreferenceStatus(userChoices.get(preference.getPurpose()));
                        preference.setStartDate(now);

                        // Recalculate end date based on preference validity
                        LocalDateTime endDate = now;
                        if (preference.getPreferenceValidity() != null) {
                            if (preference.getPreferenceValidity().getUnit().equals(Period.YEARS)) {
                                endDate = endDate.plusYears(preference.getPreferenceValidity().getValue());
                            } else if (preference.getPreferenceValidity().getUnit().equals(Period.MONTHS)) {
                                endDate = endDate.plusMonths(preference.getPreferenceValidity().getValue());
                            } else {
                                endDate = endDate.plusDays(preference.getPreferenceValidity().getValue());
                            }
                        } else {
                            // Default to 1 year if no validity specified
                            endDate = endDate.plusYears(1);
                        }
                        preference.setEndDate(endDate);
                    }
                })
                .collect(Collectors.toList());
    }

    /**
     * Save consent update with proper transaction management
     */
    @Transactional
    private UpdateConsentResponse saveConsentUpdate(Consent activeConsent, Consent newVersion,
                                                    ConsentHandle consentHandle, String tenantId) throws Exception {
        try {
            // Generate JWT token for new version
            String consentToken = generateConsentToken(newVersion);
            newVersion.setConsentJwtToken(consentToken);

            // STEP 1: Save new version first
            consentRepositoryImpl.save(newVersion, tenantId);
            log.info("Created new consent version: {} (v{}) for consentId: {}",
                    newVersion.getId(), newVersion.getVersion(), newVersion.getConsentId());

            // STEP 2: Mark previous version as UPDATED
            activeConsent.setConsentStatus(VersionStatus.UPDATED);
            activeConsent.setUpdatedAt(Instant.now());
            consentRepositoryImpl.save(activeConsent, tenantId);
            log.info("Marked previous consent version {} as UPDATED", activeConsent.getVersion());

            // STEP 3: Mark consent handle as USED
            consentHandle.setStatus(ConsentHandleStatus.USED);
            consentHandleRepository.save(consentHandle, tenantId);
            log.info("Marked consent handle as USED: {}", consentHandle.getConsentHandleId());

            return UpdateConsentResponse.success(
                    newVersion.getConsentId(),
                    newVersion.getId(),
                    newVersion.getVersion(),
                    activeConsent.getVersion(),
                    consentToken,
                    newVersion.getEndDate()
            );

        } catch (Exception e) {
            log.error("Error saving consent update, rolling back", e);
            // In a real application, transaction management would handle rollback
            // For now, we rely on @Transactional annotation
            throw new ConsentException(ErrorCodes.INTERNAL_ERROR,
                    ErrorCodes.getDescription(ErrorCodes.INTERNAL_ERROR),
                    "Error retrieving consent version: " + e.getMessage());
        }
    }

    /**
     * Generate JWT token for consent
     */
    private String generateConsentToken(Consent consent) throws Exception {
        Gson gson = new GsonBuilder()
                .registerTypeAdapter(LocalDateTime.class, new LocalDateTypeAdapter())
                .registerTypeAdapter(Instant.class, new InstantTypeAdapter())
                .create();
        String consentJsonString = gson.toJson(consent);
        return tokenUtility.generateToken(consentJsonString,
                Date.from(consent.getEndDate().atZone(ZoneId.systemDefault()).toInstant()));
    }

    /**
     * Calculate consent expiry based on preference validities
     */
    private LocalDateTime calculateConsentExpiry(List<Preference> preferences) {
        return preferences.stream()
                .map(Preference::getEndDate)
                .filter(Objects::nonNull)
                .max(LocalDateTime::compareTo)
                .orElse(LocalDateTime.now().plusYears(1)); // Default to 1 year
    }

    /**
     * Determine consent status based on preference statuses
     */
    private Status determineConsentStatus(List<Preference> preferences) {
        boolean hasAccept = preferences.stream()
                .anyMatch(preference -> preference.getPreferenceStatus().equals(PreferenceStatus.ACCEPTED));

        boolean allReject = preferences.stream()
                .allMatch(preference -> preference.getPreferenceStatus().equals(PreferenceStatus.NOTACCEPTED));

        boolean allExpired = preferences.stream()
                .allMatch(preference -> preference.getPreferenceStatus().equals(PreferenceStatus.EXPIRED));

        if (hasAccept || allReject) {
            return Status.ACTIVE;
        } else if (allExpired) {
            return Status.EXPIRED;
        }

        return Status.INACTIVE;
    }
}