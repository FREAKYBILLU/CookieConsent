package com.example.scanner.entity;

import com.example.scanner.dto.*;
import com.example.scanner.dto.request.CreateTemplateRequest;
import com.example.scanner.enums.TemplateStatus;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;

import java.time.Instant;
import java.util.List;

@Document(collection = "consent_templates")
@Data
public class ConsentTemplate {

    @Id
    @JsonProperty("_id")
    private String id;

    @Field("scanId")
    @JsonProperty("scanId")
    private String scanId;

    @Field("templateName")
    @JsonProperty("templateName")
    private String templateName;

    @Field("businessId")
    @JsonProperty("businessId")
    private String businessId;

    @Field("status")
    @JsonProperty("status")
    private TemplateStatus status;

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
    private Integer version;

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
    }

    // Helper method to create from CreateTemplateRequest
    public static ConsentTemplate fromCreateRequest(CreateTemplateRequest request, String scanId) {
        ConsentTemplate template = new ConsentTemplate();
        template.setScanId(scanId);
        template.setTemplateName(request.getTemplateName());
        template.setBusinessId(request.getBusinessId());
        template.setStatus(request.getStatus() != null ? request.getStatus() : TemplateStatus.DRAFT);
        template.setMultilingual(request.getMultilingual());
        template.setUiConfig(request.getUiConfig());
        template.setPrivacyPolicyDocument(request.getPrivacyPolicyDocument());
        template.setDocumentMeta(request.getPrivacyPolicyDocumentMeta());
        template.setPreferences(request.getPreferences());
        return template;
    }

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
