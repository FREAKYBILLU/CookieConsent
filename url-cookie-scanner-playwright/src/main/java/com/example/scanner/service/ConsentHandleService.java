package com.example.scanner.service;

import com.example.scanner.config.TenantContext;
import com.example.scanner.constants.Constants;
import com.example.scanner.constants.ErrorCodes;
import com.example.scanner.dto.request.CreateHandleRequest;
import com.example.scanner.dto.response.GetHandleResponse;
import com.example.scanner.entity.ConsentHandle;
import com.example.scanner.entity.ConsentTemplate;
import com.example.scanner.enums.ConsentHandleStatus;
import com.example.scanner.exception.ScannerException;
import com.example.scanner.repository.ConsentHandleRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.ObjectUtils;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class ConsentHandleService {

    private final ConsentHandleRepository consentHandleRepository;
    private final ConsentTemplateService consentTemplateService;

    public ConsentHandle createConsentHandle(String tenantId, CreateHandleRequest request, Map<String, String> headers)
            throws ScannerException {

        if (tenantId == null || tenantId.trim().isEmpty()) {
            throw new ScannerException(ErrorCodes.VALIDATION_ERROR,
                    "Tenant ID is required",
                    "Missing X-Tenant-ID header");
        }

        TenantContext.setCurrentTenant(tenantId);

        try {
            // Check for existing handle
            Map<String, Object> handleSearchParams = Map.of(
                    "templateId", request.getTemplateId(),
                    "templateVersion", request.getTemplateVersion(),
                    "customerIdentifiers.type", request.getCustomerIdentifiers().getType(),
                    "customerIdentifiers.value", request.getCustomerIdentifiers().getValue()
            );

            List<ConsentHandle> existingHandles = this.consentHandleRepository.findConsentHandleByParams(handleSearchParams, tenantId);
            if (!ObjectUtils.isEmpty(existingHandles) && !existingHandles.isEmpty()) {
                log.warn("Consent handle already exists for templateId: {}, customer: {}",
                        request.getTemplateId(), request.getCustomerIdentifiers().getValue());
                throw new ScannerException(ErrorCodes.DUPLICATE_ERROR,
                        "Consent handle already exists for this template and customer",
                        "Duplicate consent handle found for templateId: " + request.getTemplateId());
            }

            // Validate template exists
            validateTemplate(tenantId, request.getTemplateId());

            String consentHandleId = UUID.randomUUID().toString();

            ConsentHandle consentHandle;
            // Set timestamps and expiry
            consentHandle = new ConsentHandle(
                    consentHandleId,
                    headers.get(Constants.BUSINESS_ID_HEADER),
                    headers.get(Constants.TXN_ID),
                    request.getTemplateId(),
                    request.getTemplateVersion(),
                    request.getCustomerIdentifiers(),
                    ConsentHandleStatus.PENDING
            );

            ConsentHandle savedHandle = this.consentHandleRepository.save(consentHandle, tenantId);

            log.info("Created consent handle with ID: {} for template: {} and tenant: {}",
                    consentHandleId, request.getTemplateId(), tenantId);

            return savedHandle;

        } finally {
            TenantContext.clear();
        }
    }

    public GetHandleResponse getConsentHandleById(String consentHandleId, String tenantId)
            throws ScannerException {

        if (tenantId == null || tenantId.trim().isEmpty()) {
            throw new ScannerException(ErrorCodes.VALIDATION_ERROR,
                    "Tenant ID is required",
                    "Missing tenant ID for consent handle retrieval");
        }

        try {
            ConsentHandle consentHandle = this.consentHandleRepository.getByConsentHandleId(consentHandleId, tenantId);
            if (ObjectUtils.isEmpty(consentHandle)) {
                log.warn("Consent handle not found: {}", consentHandleId);
                throw new ScannerException(ErrorCodes.NOT_FOUND,
                        "Consent handle not found",
                        "No consent handle found with ID: " + consentHandleId);
            }

            // Get template information
            Optional<ConsentTemplate> templateOpt = consentTemplateService.getTemplateByTenantAndScanId(
                    tenantId, consentHandle.getTemplateId());

            if (templateOpt.isEmpty()) {
                log.warn("Template not found for consent handle: {}", consentHandleId);
                throw new ScannerException(ErrorCodes.NOT_FOUND,
                        "Template not found",
                        "Template not found for consent handle: " + consentHandleId);
            }

            ConsentTemplate template = templateOpt.get();

            GetHandleResponse response = GetHandleResponse.builder()
                    .consentHandleId(consentHandle.getConsentHandleId())
                    .templateId(template.getId())
                    .templateName(template.getTemplateName())
                    .templateVersion(consentHandle.getTemplateVersion())
                    .businessId(consentHandle.getBusinessId())
                    .multilingual(template.getMultilingual())
                    .preferences(template.getPreferences())
                    .customerIdentifiers(consentHandle.getCustomerIdentifiers())
                    .status(consentHandle.getStatus())
                    .build();

            log.info("Retrieved consent handle: {} for tenant: {}", consentHandleId, tenantId);

            return response;

        } finally {
            TenantContext.clear();
        }
    }

    private void validateTemplate(String tenantId, String templateId) throws ScannerException {
        // Check if template exists using the existing ConsentTemplateService
        Optional<ConsentTemplate> templateOpt = consentTemplateService.getTemplateByTenantAndTemplateId(tenantId, templateId);

        if (templateOpt.isEmpty()) {
            throw new ScannerException(ErrorCodes.NOT_FOUND,
                    "Template not found",
                    "Template with ID " + templateId + " does not exist for tenant " + tenantId);
        }

        ConsentTemplate template = templateOpt.get();

        // Validate template is published
        if (!"PUBLISHED".equals(template.getStatus().name())) {
            throw new ScannerException(ErrorCodes.INVALID_STATE_ERROR,
                    "Template is not published",
                    "Template " + templateId + " has status: " + template.getStatus());
        }
    }
}