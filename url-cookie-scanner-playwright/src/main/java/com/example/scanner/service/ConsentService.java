package com.example.scanner.service;

import com.example.scanner.constants.ErrorCodes;
import com.example.scanner.dto.Preference;
import com.example.scanner.dto.request.CreateConsentRequest;
import com.example.scanner.dto.response.ConsentCreateResponse;
import com.example.scanner.dto.response.ConsentTokenValidateResponse;
import com.example.scanner.entity.Consent;
import com.example.scanner.entity.ConsentHandle;
import com.example.scanner.entity.ConsentTemplate;
import com.example.scanner.enums.ConsentHandleStatus;
import com.example.scanner.enums.Period;
import com.example.scanner.enums.PreferenceStatus;
import com.example.scanner.enums.Status;
import com.example.scanner.exception.ConsentException;
import com.example.scanner.repository.ConsentHandleRepository;
import com.example.scanner.repository.ConsentRepository;
import com.example.scanner.util.LocalDateTypeAdapter;
import com.example.scanner.util.TokenUtility;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.ObjectUtils;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.*;

@Service
@RequiredArgsConstructor
@Slf4j
public class ConsentService {

    private final ConsentHandleRepository consentHandleRepository;
    private final ConsentTemplateService templateService;
    private final TokenUtility tokenUtility;
    private final ConsentRepository consentRepository;

    public ConsentCreateResponse createConsentByConsentHandleId(CreateConsentRequest request, String tenantId) throws Exception {
        log.info("Processing consent creation for handle: {}, tenant: {}", request.getConsentHandleId(), tenantId);

        // Validate consent handle exists and is valid
        ConsentHandle currentHandle = this.consentHandleRepository.getByConsentHandleId(request.getConsentHandleId(), tenantId);
        if (ObjectUtils.isEmpty(currentHandle)) {
            log.error("Consent handle not found: {}", request.getConsentHandleId());
            throw new ConsentException(ErrorCodes.JCMP3003);
        }

        // Check if consent handle is already used
        if (currentHandle.getStatus().equals(ConsentHandleStatus.USED)) {
            log.error("Consent handle already used: {}", request.getConsentHandleId());
            throw new ConsentException(ErrorCodes.JCMP3004);
        }

        // Check if consent handle is expired
        if (currentHandle.isExpired()) {
            log.error("Consent handle expired: {}", request.getConsentHandleId());
            throw new ConsentException(ErrorCodes.JCMP3005);
        }

        // Check if consent already exists for this template and customer
        Consent isAlreadyExist = this.consentRepository.existByTemplateIdAndTemplateVersionAndCustomerIdentifiers(
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
        Optional<ConsentTemplate> templateOpt = this.templateService.getTemplateByTenantAndScanId(tenantId, currentHandle.getTemplateId());
        if (templateOpt.isEmpty()) {
            log.error("Template not found for templateId: {}", currentHandle.getTemplateId());
            throw new ConsentException(ErrorCodes.JCMP3002);
        }
        ConsentTemplate template = templateOpt.get();

        String consentId = UUID.randomUUID().toString();
        LocalDateTime now = LocalDateTime.now();
        final LocalDateTime[] consentExpiryList = {now};
        List<Preference> updatedPreferences = new ArrayList<>();

        // Process user preferences and update template preferences
        template.getPreferences().stream()
                .peek(preference -> {
                    if (request.getPreferencesStatus().containsKey(preference.getPreferenceId())) {
                        preference.setPreferenceStatus(request.getPreferencesStatus().get(preference.getPreferenceId()));
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
                    }
                });

        LocalDateTime consentExpiry = consentExpiryList[0];

        // Create consent entity
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
                .startDate(now)
                .endDate(consentExpiry)
                .createdAt(now)
                .updatedAt(now)
                .className("com.example.scanner.entity.Consent")
                .build();

        // Generate JWT token
        Gson gson = new GsonBuilder()
                .registerTypeAdapter(LocalDateTime.class, new LocalDateTypeAdapter())
                .create();
        String consentJsonString = gson.toJson(consent);
        String consentToken = this.tokenUtility.generateToken(consentJsonString,
                Date.from(consent.getEndDate().atZone(ZoneId.systemDefault()).toInstant()));

        consent.setConsentJwtToken(consentToken);

        // Save consent and update consent handle status
        try {
            this.consentRepository.save(consent, tenantId);
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

    public ConsentTokenValidateResponse validateConsentToken(String token) throws Exception {
        return this.tokenUtility.verifyConsentToken(token);
    }
}