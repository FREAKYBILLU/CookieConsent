package com.example.scanner.service;
import com.example.scanner.constants.ErrorCodes;
import com.example.scanner.dto.request.CreateConsentRequest;
import com.example.scanner.dto.response.ConsentCreateResponse;
import com.example.scanner.entity.ConsentHandle;
import com.example.scanner.entity.ConsentTemplate;
import com.example.scanner.repository.ConsentHandleRepository;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.ObjectUtils;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.*;

@Service
@RequiredArgsConstructor
public class ConsentService {

    ConsentHandleRepository consentHandleRepository;
    ConsentTemplate templateService;
    TokenUtility tokenUtility;
    ConsentRepository consentRepository;


    public ConsentCreateResponse createConsentByConsentHandleId(CreateConsentRequest request) throws Exception {
        ConsentHandle currentHandle = this.consentHandleRepository.getByConsentHandleId(request.getConsentHandleId());
        if (ObjectUtils.isEmpty(currentHandle)) {
            throw new ConsentException(ErrorCodes.JCMP3003);
        }

        if (currentHandle.getStatus().equals(ConsentHandleStatus.COMPLETED)) {
            throw new ConsentException(ErrorCodes.JCMP3004);
        }

        Consent isAlreadyExist = this.consentRepository.existByTemplateIdAndTemplateVersionAndCustomerIdentifiers(currentHandle.getTemplateId(), currentHandle.getTemplateVersion(), currentHandle.getCustomerIdentifiers());
        if (!ObjectUtils.isEmpty(isAlreadyExist)) {
            return ConsentCreateResponse.builder()
                    .consentId(isAlreadyExist.getConsentId())
                    .consentJwtToken(isAlreadyExist.getConsentJwtToken())
                    .message("Consent already exists!")
                    .consentExpiry(isAlreadyExist.getEndDate())
                    .build();
        }
        Map<String, Object> templateSearchParams = Map.of(
                "templateId", currentHandle.getTemplateId(),
                "version", currentHandle.getTemplateVersion()
        );
        SearchResponse<Template> searchTemplates = this.templateService.searchTemplates(templateSearchParams);
        List<Template> templates = searchTemplates.getSearchList();
        if (ObjectUtils.isEmpty(templates) || templates.isEmpty()) {
            throw new ConsentException(ErrorCodes.JCMP3002);
        }
        Template template = templates.getFirst();

        String consentId = UUID.randomUUID().toString();
        LocalDateTime now = LocalDateTime.now();
        final LocalDateTime[] consentExpiryList = {now};
        List<Preference> updatedPreferences = new ArrayList<>();
        template.getPreferences().stream()
                .peek(preference -> {
                    if (request.getPreferencesStatus().containsKey(preference.getPreferenceId())) {
                        preference.setPreferenceStatus(request.getPreferencesStatus().get(preference.getPreferenceId()));
                        preference.setStartDate(now);
                        LocalDateTime endDate = now;
                        if (preference.getPreferenceValidity().getUnit().equals(Period.YEARS)) {
                            endDate = endDate.plusYears(preference.getPreferenceValidity().getValue());
                        } else if (preference.getPreferenceValidity().getUnit().equals(Period.MONTHS)) {
                            endDate = endDate.plusMonths(preference.getPreferenceValidity().getValue());
                        } else {
                            endDate = endDate.plusDays(preference.getPreferenceValidity().getValue());
                        }
                        preference.setEndDate(endDate);
                        if (endDate.isAfter(consentExpiryList[0])) {
                            consentExpiryList[0] = endDate;
                        }
                        updatedPreferences.add(preference);
                    }
                });

        LocalDateTime consentExpiry = consentExpiryList[0];
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
                .build();

        Gson gson = new GsonBuilder()
                .registerTypeAdapter(LocalDateTime.class, new LocalDateTypeAdapter())
                .create();
        String consentJsonString = gson.toJson(consent);
        String consentToken = this.tokenUtility.generateToken(consentJsonString, Date.from(consent.getEndDate().atZone(ZoneId.systemDefault()).toInstant()));

        consent.setConsentJwtToken(consentToken);
        try {
            this.consentRepository.save(consent);
            currentHandle.setStatus(ConsentHandleStatus.COMPLETED);
            this.consentHandleRepository.save(currentHandle);
        } catch (Exception e) {
            throw e;
        }

        ConsentCreateResponse response = ConsentCreateResponse.builder()
                .consentId(consentId)
                .consentJwtToken(consentToken)
                .message("Consent created Successfully!")
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