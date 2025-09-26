package com.example.scanner.dto.response;

import com.example.scanner.dto.CustomerIdentifiers;
import com.example.scanner.dto.Multilingual;
import com.example.scanner.enums.Status;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

@Data
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
@AllArgsConstructor
@NoArgsConstructor
public class ConsentCreateResponse {
    private String id;
    private String consentId;
    private String secureCode;
    private String templateId;
    private Integer templateVersion;
    private String businessId;
    private String languagePreferences;
    private Multilingual multilingual;
    private List<String> preferences;
    private CustomerIdentifiers customerIdentifiers;
    private LocalDateTime startDate;
    private LocalDateTime endDate;
    private Status status;
    private String consentJwtToken;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private String className;
    private String message;
    private LocalDateTime consentExpiry;
}
