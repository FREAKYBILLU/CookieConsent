package com.example.scanner.entity;

import com.example.scanner.dto.CustomerIdentifiers;
import com.example.scanner.dto.Multilingual;
import com.example.scanner.dto.Preference;
import com.example.scanner.dto.request.UpdateConsentRequest;
import com.example.scanner.enums.PreferenceStatus;
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
import java.util.Map;
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
}