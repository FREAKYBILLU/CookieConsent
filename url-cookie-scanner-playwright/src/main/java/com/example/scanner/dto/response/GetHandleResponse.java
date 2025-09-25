package com.example.scanner.dto.response;

import com.example.scanner.dto.CustomerIdentifiers;
import com.example.scanner.dto.Multilingual;
import com.example.scanner.dto.Preference;
import com.example.scanner.enums.ConsentHandleStatus;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
@AllArgsConstructor
@NoArgsConstructor
public class GetHandleResponse {
    private String consentHandleId;
    private String templateId;
    private String templateName;
    private int templateVersion;
    private String businessId;
    private Multilingual multilingual;
    private List<Preference> preferences;
    private CustomerIdentifiers customerIdentifiers;
    private ConsentHandleStatus status;
}