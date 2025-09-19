package com.example.scanner.entity;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import java.util.List;
import java.util.Map;

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
    private String status;

    @Field("multilingual")
    @JsonProperty("multilingual")
    private Multilingual multilingual;

    @Field("uiConfig")
    @JsonProperty("uiConfig")
    private UiConfig uiConfig;

    @Field("documentMeta")
    @JsonProperty("documentMeta")
    private DocumentMeta documentMeta;

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
        this.className = "com.jio.consent.entity.ConsentTemplate";
        this.version = 1;
        this.status = "DRAFT";
    }
}

@Data
@NoArgsConstructor
class Multilingual {
    @JsonProperty("supportedLanguages")
    private List<String> supportedLanguages;

    @JsonProperty("languageSpecificContentMap")
    private Map<String, LanguageContent> languageSpecificContentMap;

}

@Data
@NoArgsConstructor
class LanguageContent {
    @JsonProperty("description")
    private String description;

    @JsonProperty("label")
    private String label;

    @JsonProperty("rightsText")
    private String rightsText;

    @JsonProperty("permissionText")
    private String permissionText;

}

@Data
class UiConfig {
    @JsonProperty("logo")
    private String logo;

    @JsonProperty("theme")
    private String theme; // Increased size for theme datatype

    @JsonProperty("darkMode")
    private Boolean darkMode;

    @JsonProperty("mobileView")
    private Boolean mobileView;

    @JsonProperty("parentalControl")
    private Boolean parentalControl;

    @JsonProperty("dataTypeToBeShown")
    private Boolean dataTypeToBeShown;

    @JsonProperty("dataItemToBeShown")
    private Boolean dataItemToBeShown;

    @JsonProperty("processActivityNameToBeShown")
    private Boolean processActivityNameToBeShown;

    @JsonProperty("processorNameToBeShown")
    private Boolean processorNameToBeShown;

    @JsonProperty("validitytoBeShown")
    private Boolean validitytoBeShown;

    public UiConfig() {
        this.darkMode = false;
        this.mobileView = true;
        this.parentalControl = false;
        this.dataTypeToBeShown = true;
        this.dataItemToBeShown = true;
        this.processActivityNameToBeShown = true;
        this.processorNameToBeShown = true;
        this.validitytoBeShown = true;
    }
}

@Data
@NoArgsConstructor
class DocumentMeta {
    @JsonProperty("documentId")
    private String documentId;

    @JsonProperty("name")
    private String name;

    @JsonProperty("contentType")
    private String contentType;

    @JsonProperty("size")
    private Long size;


}
@Data
@NoArgsConstructor
class Preference {
    @JsonProperty("preferenceId")
    private String preferenceId;

    @JsonProperty("purposes")
    private String purpose;

    @JsonProperty("isMandatory")
    private Boolean isMandatory;

    @JsonProperty("autoRenew")
    private Boolean autoRenew;

    @JsonProperty("preferenceValidity")
    private PreferenceValidity preferenceValidity;

    @JsonProperty("processorActivityIds")
    private List<String> processorActivityIds;

}

@Data
@NoArgsConstructor
class PreferenceValidity {
    @JsonProperty("value")
    private Integer value;

    @JsonProperty("unit")
    private String unit;


}
