package com.example.scanner.util;

import com.example.scanner.dto.Preference;
import com.example.scanner.dto.request.UpdateConsentRequest;
import com.example.scanner.entity.Consent;
import com.example.scanner.enums.PreferenceStatus;
import com.example.scanner.enums.Status;
import com.example.scanner.enums.VersionStatus;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Utility class for Consent entity operations
 * Keeps business logic separate from entity classes
 */
public class ConsentUtil {

    /**
     * Create new version from existing consent with updates
     * Used for consent update operations
     */
    public static Consent createNewVersionFrom(Consent existingConsent, UpdateConsentRequest updateRequest,
                                               String newConsentHandleId, Integer templateVersionFromHandle) {
        Consent newVersion = new Consent();

        // Copy immutable fields
        newVersion.setConsentId(existingConsent.getConsentId());
        newVersion.setBusinessId(existingConsent.getBusinessId());
        newVersion.setTemplateId(existingConsent.getTemplateId());
        newVersion.setCustomerIdentifiers(existingConsent.getCustomerIdentifiers());

        // Set version information
        newVersion.setVersion(existingConsent.getVersion() + 1);
        newVersion.setConsentStatus(VersionStatus.ACTIVE);

        // Set new consent handle
        newVersion.setConsentHandleId(newConsentHandleId);

        // Update template version if provided
        newVersion.setTemplateVersion(templateVersionFromHandle);

        // Apply updates from request
        newVersion.setLanguagePreferences(updateRequest.getLanguagePreference() != null ?
                updateRequest.getLanguagePreference() : existingConsent.getLanguagePreferences());

        newVersion.setPreferences(updateRequest.getPreferencesStatus() != null ?
                processUpdatedPreferences(existingConsent.getPreferences(), updateRequest.getPreferencesStatus()) :
                existingConsent.getPreferences());

        // Copy other fields
        newVersion.setMultilingual(existingConsent.getMultilingual());

        // Set timestamps and status
        LocalDateTime now = LocalDateTime.now();
        newVersion.setStartDate(now);
        newVersion.setCreatedAt(Instant.now());
        newVersion.setUpdatedAt(Instant.now());
        newVersion.setClassName("com.example.scanner.entity.Consent");

        // Calculate new end date based on preferences
        newVersion.setEndDate(calculateConsentEndDate(newVersion.getPreferences()));

        // Determine consent status based on preferences
        newVersion.setStatus(determineConsentStatus(newVersion.getPreferences()));

        return newVersion;
    }

    /**
     * Process updated preferences with user's new choices
     */
    public static List<Preference> processUpdatedPreferences(List<Preference> existingPreferences,
                                                             Map<String, PreferenceStatus> userChoices) {
        return existingPreferences.stream()
                .peek(preference -> {
                    if (userChoices.containsKey(preference.getPurpose())) {
                        preference.setPreferenceStatus(userChoices.get(preference.getPurpose()));
                        preference.setStartDate(LocalDateTime.now());
                        // Recalculate end date based on preference validity
                        // This should ideally be done in service layer with proper calculation
                    }
                })
                .collect(Collectors.toList());
    }

    /**
     * Calculate consent end date based on preference validities
     */
    public static LocalDateTime calculateConsentEndDate(List<Preference> preferences) {
        return preferences.stream()
                .map(Preference::getEndDate)
                .filter(java.util.Objects::nonNull)
                .max(LocalDateTime::compareTo)
                .orElse(LocalDateTime.now().plusYears(1));
    }

    /**
     * Determine consent status based on preference statuses
     */
    public static Status determineConsentStatus(List<Preference> preferences) {
        boolean hasAccept = preferences.stream()
                .anyMatch(pref -> pref.getPreferenceStatus() == PreferenceStatus.ACCEPTED);

        boolean allReject = preferences.stream()
                .allMatch(pref -> pref.getPreferenceStatus() == PreferenceStatus.NOTACCEPTED);

        if (hasAccept || allReject) {
            return Status.ACTIVE;
        }

        return Status.INACTIVE;
    }
}