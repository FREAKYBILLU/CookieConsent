package com.example.scanner.entity;

import com.example.scanner.dto.CustomerIdentifiers;
import com.example.scanner.dto.Multilingual;
import com.example.scanner.dto.Preference;
import com.example.scanner.enums.Status;
import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;

import java.time.LocalDateTime;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "consents")
public class Consent {

    @Id
    private String id;

    @Field("consentId")
    private String consentId;

    @Field("consentHandleId")
    private String consentHandleId;

    @Field("businessId")
    private String businessId;

    @Field("templateId")
    private String templateId;

    @Field("templateVersion")
    private Integer templateVersion;

    @Field("languagePreferences")
    private String languagePreferences;

    @Field("multilingual")
    private Multilingual multilingual;

    @Field("customerIdentifiers")
    private CustomerIdentifiers customerIdentifiers;

    @Field("preferences")
    private List<Preference> preferences;

    @Field("status")
    private Status status;

    @Field("startDate")
    @JsonFormat(shape = JsonFormat.Shape.STRING, timezone = "UTC")
    private LocalDateTime startDate;

    @Field("endDate")
    @JsonFormat(shape = JsonFormat.Shape.STRING, timezone = "UTC")
    private LocalDateTime endDate;

    @Field("consentJwtToken")
    private String consentJwtToken;

    @Field("createdAt")
    @JsonFormat(shape = JsonFormat.Shape.STRING, timezone = "UTC")
    private LocalDateTime createdAt;

    @Field("updatedAt")
    @JsonFormat(shape = JsonFormat.Shape.STRING, timezone = "UTC")
    private LocalDateTime updatedAt;

    @Field("_class")
    private String className;
}