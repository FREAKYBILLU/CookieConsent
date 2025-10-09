package com.example.scanner.service;

import com.example.scanner.config.TenantContext;
import com.example.scanner.constants.Constants;
import com.example.scanner.constants.ErrorCodes;
import com.example.scanner.dto.request.CreateHandleRequest;
import com.example.scanner.dto.response.GetHandleResponse;
import com.example.scanner.entity.ConsentHandle;
import com.example.scanner.entity.ConsentTemplate;
import com.example.scanner.enums.ConsentHandleStatus;
import com.example.scanner.exception.ConsentHandleExpiredException;
import com.example.scanner.exception.ScannerException;
import com.example.scanner.repository.ConsentHandleRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.util.ObjectUtils;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class ConsentHandleService {

    private final ConsentHandleRepository consentHandleRepository;
    private final ConsentTemplateService consentTemplateService;

    @Value("${consent.handle.expiry.minutes:15}")
    private int handleExpiryMinutes;

    public ConsentHandle createConsentHandle(String tenantId, CreateHandleRequest request, Map<String, String> headers)
            throws ScannerException {

        if (tenantId == null || tenantId.trim().isEmpty()) {
            throw new ScannerException(ErrorCodes.VALIDATION_ERROR,
                    "Tenant ID is required",
                    "Missing X-Tenant-ID header");
        }

        TenantContext.setCurrentTenant(tenantId);

        try {
            validateTemplate(tenantId, request.getTemplateId(), headers.get(Constants.BUSINESS_ID_HEADER));

            String consentHandleId = UUID.randomUUID().toString();

            ConsentHandle consentHandle = new ConsentHandle(
                    consentHandleId,
                    headers.get(Constants.BUSINESS_ID_HEADER),
                    headers.get(Constants.TXN_ID),
                    request.getTemplateId(),
                    request.getTemplateVersion(),
                    request.getCustomerIdentifiers(),
                    ConsentHandleStatus.PENDING,
                    handleExpiryMinutes
            );

            ConsentHandle savedHandle = this.consentHandleRepository.save(consentHandle, tenantId);

            log.info("Created consent handle with ID: {} for template: {} and tenant: {}",
                    consentHandleId, request.getTemplateId(), tenantId);

            return savedHandle;

        } catch (IllegalStateException e) {
            if (e.getMessage().contains("Database") && e.getMessage().contains("does not exist")) {
                log.error("Database does not exist for tenant: {}", tenantId);
                throw new ScannerException(ErrorCodes.VALIDATION_ERROR,
                        "Invalid tenant - database does not exist",
                        "Database 'template_" + tenantId + "' does not exist. Please check the tenant ID.");
            }
            throw e;
        } catch (ScannerException e) {
            throw e;
        } catch (DataAccessException e) {
            log.error("Database access error for tenant {}: {}", tenantId, e.getMessage());
            throw new ScannerException(ErrorCodes.INTERNAL_ERROR,
                    "Database access error",
                    "Failed to access tenant database: " + e.getMessage());
        } catch (Exception e) {
            log.error("Unexpected error creating consent handle for tenant: {}", tenantId, e);
            throw new ScannerException(ErrorCodes.INTERNAL_ERROR,
                    "Failed to create consent handle",
                    "Unexpected error: " + e.getMessage());
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

            validateNotExpired(consentHandle, tenantId);

            // Get template information
            Optional<ConsentTemplate> templateOpt = consentTemplateService.getTemplateByTenantAndTemplateIdAndTemplateVersion(
                    tenantId, consentHandle.getTemplateId(), consentHandle.getTemplateVersion());

            if (templateOpt.isEmpty()) {
                log.warn("Template not found for consent handle: {}", consentHandleId);
                throw new ScannerException(ErrorCodes.NOT_FOUND,
                        "Template not found",
                        "Template not found for consent handle: " + consentHandleId);
            }

            ConsentTemplate template = templateOpt.get();

            GetHandleResponse response = GetHandleResponse.builder()
                    .consentHandleId(consentHandle.getConsentHandleId())
                    .templateId(template.getTemplateId())
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

    private void validateTemplate(String tenantId, String templateId, String businessid) throws ScannerException {
        // Check if template exists using the existing ConsentTemplateService
        Optional<ConsentTemplate> templateOpt = consentTemplateService.getTemplateByTenantAndTemplateIdAndBusinessId(tenantId, templateId, businessid);

        if (templateOpt.isEmpty()) {
            throw new ScannerException(ErrorCodes.NOT_FOUND,
                    "Template not found",
                    "Template with ID " + templateId + " and business Id " + businessid + " with status PUBLISHED and Template Status ACTIVE does not exist for tenant " + tenantId);
        }

        ConsentTemplate template = templateOpt.get();

        // Validate template is published
        if (!"PUBLISHED".equals(template.getStatus().name())) {
            throw new ScannerException(ErrorCodes.INVALID_STATE_ERROR,
                    "Template is not published",
                    "Template " + templateId + " has status: " + template.getStatus());
        }
    }

    private void validateNotExpired(ConsentHandle handle, String tenantId) {
        if (handle.isExpired()) {
            handle.setStatus(ConsentHandleStatus.EXPIRED);
            consentHandleRepository.save(handle, tenantId);

            throw new ConsentHandleExpiredException(
                    ErrorCodes.CONSENT_HANDLE_EXPIRED,
                    "Consent handle has expired",
                    "Consent handle " + handle.getConsentHandleId() + " expired at " + handle.getExpiresAt()
            );
        }
    }
}