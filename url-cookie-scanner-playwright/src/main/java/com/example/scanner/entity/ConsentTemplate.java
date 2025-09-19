package com.example.scanner.entity;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import java.util.List;
import java.util.Map;

@Document(collection = "consent_templates")
public class ConsentTemplate {

    @Id
    @JsonProperty("_id")
    private String id;

    @Field("scanId")
    @JsonProperty("scanId")
    private String scanId; // Changed from templateId to scanId

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
    private Instant createdAt;

    @Field("updatedAt")
    @JsonProperty("updatedAt")
    private Instant updatedAt;

    @Field("_class")
    @JsonProperty("_class")
    private String className;

    // Constructors
    public ConsentTemplate() {
        this.createdAt = Instant.now();
        this.updatedAt = Instant.now();
        this.className = "com.jio.consent.entity.ConsentTemplate";
        this.version = 1;
        this.status = "DRAFT";
    }

    // Getters and Setters
    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getScanId() { return scanId; }
    public void setScanId(String scanId) { this.scanId = scanId; }

    public String getTemplateName() { return templateName; }
    public void setTemplateName(String templateName) { this.templateName = templateName; }

    public String getBusinessId() { return businessId; }
    public void setBusinessId(String businessId) { this.businessId = businessId; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public Multilingual getMultilingual() { return multilingual; }
    public void setMultilingual(Multilingual multilingual) { this.multilingual = multilingual; }

    public UiConfig getUiConfig() { return uiConfig; }
    public void setUiConfig(UiConfig uiConfig) { this.uiConfig = uiConfig; }

    public DocumentMeta getDocumentMeta() { return documentMeta; }
    public void setDocumentMeta(DocumentMeta documentMeta) { this.documentMeta = documentMeta; }

    public List<Preference> getPreferences() { return preferences; }
    public void setPreferences(List<Preference> preferences) { this.preferences = preferences; }

    public Integer getVersion() { return version; }
    public void setVersion(Integer version) { this.version = version; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }

    public String getClassName() { return className; }
    public void setClassName(String className) { this.className = className; }
}

// Multilingual.java
class Multilingual {
    @JsonProperty("supportedLanguages")
    private List<String> supportedLanguages;

    @JsonProperty("languageSpecificContentMap")
    private Map<String, LanguageContent> languageSpecificContentMap;

    // Constructors, getters, setters
    public Multilingual() {}

    public List<String> getSupportedLanguages() { return supportedLanguages; }
    public void setSupportedLanguages(List<String> supportedLanguages) { this.supportedLanguages = supportedLanguages; }

    public Map<String, LanguageContent> getLanguageSpecificContentMap() { return languageSpecificContentMap; }
    public void setLanguageSpecificContentMap(Map<String, LanguageContent> languageSpecificContentMap) {
        this.languageSpecificContentMap = languageSpecificContentMap;
    }
}

// LanguageContent.java
class LanguageContent {
    @JsonProperty("description")
    private String description;

    @JsonProperty("label")
    private String label;

    @JsonProperty("rightsText")
    private String rightsText;

    @JsonProperty("permissionText")
    private String permissionText;

    // Constructors, getters, setters
    public LanguageContent() {}

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public String getLabel() { return label; }
    public void setLabel(String label) { this.label = label; }

    public String getRightsText() { return rightsText; }
    public void setRightsText(String rightsText) { this.rightsText = rightsText; }

    public String getPermissionText() { return permissionText; }
    public void setPermissionText(String permissionText) { this.permissionText = permissionText; }
}

// UiConfig.java
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

    // Constructors, getters, setters
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

    public String getLogo() { return logo; }
    public void setLogo(String logo) { this.logo = logo; }

    public String getTheme() { return theme; }
    public void setTheme(String theme) { this.theme = theme; }

    public Boolean getDarkMode() { return darkMode; }
    public void setDarkMode(Boolean darkMode) { this.darkMode = darkMode; }

    public Boolean getMobileView() { return mobileView; }
    public void setMobileView(Boolean mobileView) { this.mobileView = mobileView; }

    public Boolean getParentalControl() { return parentalControl; }
    public void setParentalControl(Boolean parentalControl) { this.parentalControl = parentalControl; }

    public Boolean getDataTypeToBeShown() { return dataTypeToBeShown; }
    public void setDataTypeToBeShown(Boolean dataTypeToBeShown) { this.dataTypeToBeShown = dataTypeToBeShown; }

    public Boolean getDataItemToBeShown() { return dataItemToBeShown; }
    public void setDataItemToBeShown(Boolean dataItemToBeShown) { this.dataItemToBeShown = dataItemToBeShown; }

    public Boolean getProcessActivityNameToBeShown() { return processActivityNameToBeShown; }
    public void setProcessActivityNameToBeShown(Boolean processActivityNameToBeShown) {
        this.processActivityNameToBeShown = processActivityNameToBeShown;
    }

    public Boolean getProcessorNameToBeShown() { return processorNameToBeShown; }
    public void setProcessorNameToBeShown(Boolean processorNameToBeShown) {
        this.processorNameToBeShown = processorNameToBeShown;
    }

    public Boolean getValiditytoBeShown() { return validitytoBeShown; }
    public void setValiditytoBeShown(Boolean validitytoBeShown) { this.validitytoBeShown = validitytoBeShown; }
}

// DocumentMeta.java
class DocumentMeta {
    @JsonProperty("documentId")
    private String documentId;

    @JsonProperty("name")
    private String name;

    @JsonProperty("contentType")
    private String contentType;

    @JsonProperty("size")
    private Long size;

    // Constructors, getters, setters
    public DocumentMeta() {}

    public String getDocumentId() { return documentId; }
    public void setDocumentId(String documentId) { this.documentId = documentId; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getContentType() { return contentType; }
    public void setContentType(String contentType) { this.contentType = contentType; }

    public Long getSize() { return size; }
    public void setSize(Long size) { this.size = size; }
}

// Preference.java
class Preference {
    @JsonProperty("preferenceId")
    private String preferenceId;

    @JsonProperty("purposeIds")
    private List<String> purposeIds;

    @JsonProperty("isMandatory")
    private Boolean isMandatory;

    @JsonProperty("autoRenew")
    private Boolean autoRenew;

    @JsonProperty("preferenceValidity")
    private PreferenceValidity preferenceValidity;

    @JsonProperty("processorActivityIds")
    private List<String> processorActivityIds;

    // Constructors, getters, setters
    public Preference() {}

    public String getPreferenceId() { return preferenceId; }
    public void setPreferenceId(String preferenceId) { this.preferenceId = preferenceId; }

    public List<String> getPurposeIds() { return purposeIds; }
    public void setPurposeIds(List<String> purposeIds) { this.purposeIds = purposeIds; }

    public Boolean getIsMandatory() { return isMandatory; }
    public void setIsMandatory(Boolean isMandatory) { this.isMandatory = isMandatory; }

    public Boolean getAutoRenew() { return autoRenew; }
    public void setAutoRenew(Boolean autoRenew) { this.autoRenew = autoRenew; }

    public PreferenceValidity getPreferenceValidity() { return preferenceValidity; }
    public void setPreferenceValidity(PreferenceValidity preferenceValidity) {
        this.preferenceValidity = preferenceValidity;
    }

    public List<String> getProcessorActivityIds() { return processorActivityIds; }
    public void setProcessorActivityIds(List<String> processorActivityIds) {
        this.processorActivityIds = processorActivityIds;
    }
}

// PreferenceValidity.java
class PreferenceValidity {
    @JsonProperty("value")
    private Integer value;

    @JsonProperty("unit")
    private String unit;

    // Constructors, getters, setters
    public PreferenceValidity() {}

    public Integer getValue() { return value; }
    public void setValue(Integer value) { this.value = value; }

    public String getUnit() { return unit; }
    public void setUnit(String unit) { this.unit = unit; }
}
