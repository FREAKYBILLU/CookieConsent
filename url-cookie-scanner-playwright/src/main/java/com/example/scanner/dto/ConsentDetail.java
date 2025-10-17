package com.example.scanner.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Detailed consent information")
public class ConsentDetail {

    @Schema(description = "Consent UUID")
    private String consentID;

    @Schema(description = "Consent Handle UUID")
    private String consentHandle;

    @Schema(description = "Template version")
    private Integer templateVersion;

    @Schema(description = "Consent version")
    private Integer consentVersion;

    @Schema(description = "All template preferences/categories")
    private List<String> templatePreferences;

    @Schema(description = "User accepted preferences")
    private List<String> userSelectedPreference;

    @Schema(description = "Consent status (Active/Revoked/Expired/Rejected)")
    private String consentStatus;

    @Schema(description = "Consent handle status (Pending/Rejected/Accepted)")
    private String consentHandleStatus;

    @Schema(description = "Customer identifiers")
    private CustomerIdentifiers customerIdentifier;
}
