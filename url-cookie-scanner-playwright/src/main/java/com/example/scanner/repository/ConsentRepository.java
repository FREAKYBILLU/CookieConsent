package com.example.scanner.repository;

import com.example.scanner.entity.Consent;
import com.example.scanner.dto.CustomerIdentifiers;

public interface ConsentRepository {
    Consent save(Consent consent, String tenantId);
    Consent existByTemplateIdAndTemplateVersionAndCustomerIdentifiers(
            String templateId, Integer templateVersion, CustomerIdentifiers customerIdentifiers, String tenantId);
}