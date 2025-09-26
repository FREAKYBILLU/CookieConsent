package com.example.scanner.entity;

import com.example.scanner.dto.*;
import com.example.scanner.dto.request.CreateTemplateRequest;
import com.example.scanner.dto.request.UpdateTemplateRequest;
import com.example.scanner.enums.TemplateStatus;
import com.example.scanner.enums.VersionStatus;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Document(collection = "consent_templates")
@Data
public class ConsentTemplate {

    @Id
    @JsonProperty("_id")
    private String id; // Unique document ID (changes with each version)

    @Field("templateId")
    @JsonProperty("templateId")
    private String templateId; // Logical template ID (same across versions)

    @Field("scanId")
    @JsonProperty("scanId")
    private String scanId; // IMMUTABLE: Links to original scan

    @Field("templateName")
    @JsonProperty("templateName")
    private String templateName;

    @Field("businessId")
    @JsonProperty("businessId")
    private String businessId; // IMMUTABLE: Business association

    @Field("status")
    @JsonProperty("status")
    private TemplateStatus status; // DRAFT, PUBLISHED (template lifecycle)

    @Field("templateStatus")
    @JsonProperty("templateStatus")
    private VersionStatus templateStatus; // ACTIVE, UPDATED (version status)

    @Field("multilingual")
    @JsonProperty("multilingual")
    private Multilingual multilingual;

    @Field("uiConfig")
    @JsonProperty("uiConfig")
    private UiConfig uiConfig;

    @Field("documentMeta")
    @JsonProperty("documentMeta")
    private DocumentMeta documentMeta;

    @Field("privacyPolicyDocument")
    @JsonProperty("privacyPolicyDocument")
    private String privacyPolicyDocument;

    @Field("preferences")
    @JsonProperty("preferences")
    private List<Preference> preferences;

    @Field("version")
    @JsonProperty("version")
    private Integer version; // Version number (1, 2, 3...)

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

    public ConsentTemplate() {
        this.createdAt = Instant.now();
        this.updatedAt = Instant.now();
        this.className = "com.example.scanner.entity.ConsentTemplate";
        this.version = 1;
        this.status = TemplateStatus.DRAFT;
        this.templateStatus = VersionStatus.ACTIVE; // New templates are active by default
    }

    // Helper method to create from CreateTemplateRequest
    public static ConsentTemplate fromCreateRequest(CreateTemplateRequest request, String scanId) {
        ConsentTemplate template = new ConsentTemplate();
        template.setTemplateId(UUID.randomUUID().toString()); // Generate logical template ID
        template.setScanId(scanId);
        template.setTemplateName(request.getTemplateName());
        template.setBusinessId(request.getBusinessId());
        template.setStatus(request.getStatus() != null ? request.getStatus() : TemplateStatus.DRAFT);
        template.setTemplateStatus(VersionStatus.ACTIVE); // First version is always active
        template.setMultilingual(request.getMultilingual());
        template.setUiConfig(request.getUiConfig());
        template.setPrivacyPolicyDocument(request.getPrivacyPolicyDocument());
        template.setDocumentMeta(request.getPrivacyPolicyDocumentMeta());
        template.setPreferences(request.getPreferences());
        return template;
    }

    public static ConsentTemplate createNewVersionFrom(ConsentTemplate existingTemplate, UpdateTemplateRequest updateRequest) {
        ConsentTemplate newVersion = new ConsentTemplate();

        // Copy immutable fields (these NEVER change across versions)
        newVersion.setTemplateId(existingTemplate.getTemplateId()); // Same logical ID
        newVersion.setScanId(existingTemplate.getScanId()); // Same scan reference
        newVersion.setBusinessId(existingTemplate.getBusinessId()); // Same business

        // Set version information
        newVersion.setVersion(existingTemplate.getVersion() + 1); // Increment version
        newVersion.setTemplateStatus(VersionStatus.ACTIVE); // New version is active

        // Apply updates from request, keeping existing values if not provided
        newVersion.setTemplateName(updateRequest.getTemplateName() != null ?
                updateRequest.getTemplateName() : existingTemplate.getTemplateName());

        newVersion.setStatus(updateRequest.getStatus() != null ?
                updateRequest.getStatus() : existingTemplate.getStatus());

        newVersion.setMultilingual(updateRequest.getMultilingual() != null ?
                updateRequest.getMultilingual() : existingTemplate.getMultilingual());

        newVersion.setUiConfig(updateRequest.getUiConfig() != null ?
                updateRequest.getUiConfig() : existingTemplate.getUiConfig());

        newVersion.setPrivacyPolicyDocument(updateRequest.getPrivacyPolicyDocument() != null ?
                updateRequest.getPrivacyPolicyDocument() : existingTemplate.getPrivacyPolicyDocument());

        newVersion.setDocumentMeta(updateRequest.getPrivacyPolicyDocumentMeta() != null ?
                updateRequest.getPrivacyPolicyDocumentMeta() : existingTemplate.getDocumentMeta());

        newVersion.setPreferences(updateRequest.getPreferences() != null ?
                updateRequest.getPreferences() : existingTemplate.getPreferences());

        return newVersion;
    }

    // Legacy update method - kept for backward compatibility
    public void updateFromRequest(CreateTemplateRequest request) {
        if (request.getTemplateName() != null) {
            this.setTemplateName(request.getTemplateName());
        }
        if (request.getStatus() != null) {
            this.setStatus(request.getStatus());
        }
        if (request.getMultilingual() != null) {
            this.setMultilingual(request.getMultilingual());
        }
        if (request.getUiConfig() != null) {
            this.setUiConfig(request.getUiConfig());
        }
        if (request.getPrivacyPolicyDocument() != null) {
            this.setPrivacyPolicyDocument(request.getPrivacyPolicyDocument());
        }
        if (request.getPrivacyPolicyDocumentMeta() != null) {
            this.setDocumentMeta(request.getPrivacyPolicyDocumentMeta());
        }
        if (request.getPreferences() != null) {
            this.setPreferences(request.getPreferences());
        }
        this.setUpdatedAt(Instant.now());
    }
}