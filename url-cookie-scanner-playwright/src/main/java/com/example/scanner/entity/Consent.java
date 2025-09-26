package com.example.scanner.entity;

import com.example.scanner.dto.CustomerIdentifiers;
import com.example.scanner.dto.Multilingual;
import com.example.scanner.dto.Preference;
import com.example.scanner.dto.request.UpdateConsentRequest;
import com.example.scanner.enums.Status;
import com.example.scanner.enums.VersionStatus;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Document(collection = "consents")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Consent {

    @Id
    @JsonProperty("_id")
    private String id; // Unique document ID (changes with each version)

    @Field("consentId")
    @JsonProperty("consentId")
    private String consentId; // Logical consent ID (same across versions)

    @Field("consentHandleId")
    @JsonProperty("consentHandleId")
    private String consentHandleId; // Links to the handle that created this consent

    @Field("businessId")
    @JsonProperty("businessId")
    private String businessId; // IMMUTABLE: Business association

    @Field("templateId")
    @JsonProperty("templateId")
    private String templateId; // IMMUTABLE: Template association

    @Field("templateVersion")
    @JsonProperty("templateVersion")
    private Integer templateVersion; // Template version when consent was created

    @Field("languagePreferences")
    @JsonProperty("languagePreferences")
    private String languagePreferences;

    @Field("multilingual")
    @JsonProperty("multilingual")
    private Multilingual multilingual;

    @Field("customerIdentifiers")
    @JsonProperty("customerIdentifiers")
    private CustomerIdentifiers customerIdentifiers; // IMMUTABLE: Customer association

    @Field("preferences")
    @JsonProperty("preferences")
    private List<Preference> preferences; // User's preference choices

    @Field("status")
    @JsonProperty("status")
    private Status status; // ACTIVE, EXPIRED, INACTIVE (consent lifecycle)

    @Field("consentStatus")
    @JsonProperty("consentStatus")
    private VersionStatus consentStatus; // ACTIVE, UPDATED (version status)

    @Field("version")
    @JsonProperty("version")
    private Integer version; // Version number (1, 2, 3...)

    @Field("consentJwtToken")
    @JsonProperty("consentJwtToken")
    private String consentJwtToken; // JWT token for this consent version

    @Field("startDate")
    @JsonProperty("startDate")
    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime startDate;

    @Field("endDate")
    @JsonProperty("endDate")
    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime endDate;

    @Field("createdAt")
    @JsonProperty("createdAt")
    @JsonFormat(shape = JsonFormat.Shape.STRING, timezone = "UTC")
    private Instant createdAt;

    @Field("updatedAt")
    @JsonProperty("updatedAt")
    @JsonFormat(shape = JsonFormat.Shape.STRING, timezone = "UTC")
    private Instant updatedAt;

    @Field("_class")
    @JsonProperty("_class")
    private String className;

    // Constructor for new consent creation (version 1)
    public Consent(String consentHandleId, String businessId, String templateId, Integer templateVersion,
                   String languagePreferences, Multilingual multilingual, CustomerIdentifiers customerIdentifiers,
                   List<Preference> preferences, Status status, LocalDateTime startDate, LocalDateTime endDate) {
        this.consentId = UUID.randomUUID().toString(); // Generate logical consent ID
        this.consentHandleId = consentHandleId;
        this.businessId = businessId;
        this.templateId = templateId;
        this.templateVersion = templateVersion;
        this.languagePreferences = languagePreferences;
        this.multilingual = multilingual;
        this.customerIdentifiers = customerIdentifiers;
        this.preferences = preferences;
        this.status = status;
        this.consentStatus = VersionStatus.ACTIVE; // First version is always active
        this.version = 1; // First version
        this.startDate = startDate;
        this.endDate = endDate;
        this.createdAt = Instant.now();
        this.updatedAt = Instant.now();
        this.className = "com.example.scanner.entity.Consent";
    }

    /**
     * Create new version from existing consent with updates
     * Used for consent update operations
     */
    public static Consent createNewVersionFrom(Consent existingConsent, UpdateConsentRequest updateRequest,
                                               String newConsentHandleId, Integer newTemplateVersion) {
        Consent newVersion = new Consent();

        // Copy immutable fields (these NEVER change across versions)
        newVersion.setConsentId(existingConsent.getConsentId()); // Same logical ID
        newVersion.setBusinessId(existingConsent.getBusinessId()); // Same business
        newVersion.setTemplateId(existingConsent.getTemplateId()); // Same template (logical)
        newVersion.setCustomerIdentifiers(existingConsent.getCustomerIdentifiers()); // Same customer

        // Set version information
        newVersion.setVersion(existingConsent.getVersion() + 1); // Increment version
        newVersion.setConsentStatus(VersionStatus.ACTIVE); // New version is active

        // Set new consent handle (the one used for this update)
        newVersion.setConsentHandleId(newConsentHandleId);

        // Update template version if provided (consent might reference newer template)
        newVersion.setTemplateVersion(newTemplateVersion != null ?
                newTemplateVersion : existingConsent.getTemplateVersion());

        // Apply updates from request, keeping existing values if not provided
        newVersion.setLanguagePreferences(updateRequest.getLanguagePreference() != null ?
                updateRequest.getLanguagePreference() : existingConsent.getLanguagePreferences());

        newVersion.setPreferences(updateRequest.getPreferencesStatus() != null ?
                // Process new preferences with user choices
                processUpdatedPreferences(existingConsent.getPreferences(), updateRequest.getPreferencesStatus()) :
                existingConsent.getPreferences());

        // Copy other fields that may not change
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
    private static List<Preference> processUpdatedPreferences(List<Preference> existingPreferences,
                                                              java.util.Map<String, com.example.scanner.enums.PreferenceStatus> userChoices) {
        // Implementation would merge existing preferences with user's new choices
        // This follows the same logic as in ConsentService.createConsentByConsentHandleId
        // but applies updates instead of initial creation
        return existingPreferences.stream()
                .peek(preference -> {
                    if (userChoices.containsKey(preference.getPreferenceId())) {
                        preference.setPreferenceStatus(userChoices.get(preference.getPreferenceId()));
                        preference.setStartDate(LocalDateTime.now());
                        // Recalculate end date based on preference validity...
                    }
                })
                .collect(java.util.stream.Collectors.toList());
    }

    /**
     * Calculate consent end date based on preference validities
     */
    private static LocalDateTime calculateConsentEndDate(List<Preference> preferences) {
        return preferences.stream()
                .map(Preference::getEndDate)
                .filter(java.util.Objects::nonNull)
                .max(LocalDateTime::compareTo)
                .orElse(LocalDateTime.now().plusYears(1)); // Default to 1 year
    }

    /**
     * Determine consent status based on preference statuses
     */
    private static Status determineConsentStatus(List<Preference> preferences) {
        boolean hasAccept = preferences.stream()
                .anyMatch(pref -> pref.getPreferenceStatus() == com.example.scanner.enums.PreferenceStatus.ACCEPTED);

        boolean allReject = preferences.stream()
                .allMatch(pref -> pref.getPreferenceStatus() == com.example.scanner.enums.PreferenceStatus.NOTACCEPTED);

        if (hasAccept || allReject) {
            return Status.ACTIVE;
        }

        return Status.INACTIVE;
    }
}