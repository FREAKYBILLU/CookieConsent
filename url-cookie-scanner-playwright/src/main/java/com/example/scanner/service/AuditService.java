package com.example.scanner.service;

import com.example.scanner.client.AuditClient;
import com.example.scanner.constants.AuditConstants;
import com.example.scanner.dto.Actor;
import com.example.scanner.dto.request.AuditRequest;
import com.example.scanner.dto.response.AuditResponse;
import com.example.scanner.dto.Resource;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class AuditService {

    private final AuditClient auditClient;

    public void logAudit(String tenantId, String businessId, String component, String actionType,
                         String initiator, String resourceType, String resourceId) {
        try {
            String auditId = "audit-" + UUID.randomUUID();
            String transactionId = "txn-" + UUID.randomUUID();

            Actor actor = Actor.builder()
                    .id("actor-" + UUID.randomUUID())
                    .role("SYSTEM")
                    .type(AuditConstants.ACTOR_TYPE_SYSTEM)
                    .build();

            Resource resource = Resource.builder()
                    .type(resourceType)
                    .id(resourceId != null ? resourceId : "resource-" + UUID.randomUUID())
                    .build();

            Map<String, Object> context = new HashMap<>();
            context.put("txnId", transactionId);
            context.put("ipAddress", "127.0.0.1");

            Map<String, Object> extra = new HashMap<>();
            extra.put("source", "cookie-consent-service");

            AuditRequest auditRequest = AuditRequest.builder()
                    .auditId(auditId)
                    .tenantId(tenantId)
                    .businessId(businessId)
                    .transactionId(transactionId)
                    .actor(actor)
                    .group(AuditConstants.GROUP_COOKIE_CONSENT)
                    .component(component)
                    .actionType(actionType)
                    .initiator(initiator)
                    .resource(resource)
                    .payloadHash("hash-" + UUID.randomUUID())
                    .context(context)
                    .extra(extra)
                    .status("SUCCESS")
                    .timestamp(Instant.now().toString())
                    .build();

            auditClient.createAudit(auditRequest, tenantId, businessId, transactionId);

        } catch (Exception e) {
            log.error("Audit logging failed: {}", e.getMessage());
        }
    }

    // ========================================
    // COOKIE SCAN METHODS (3) - Pass null for businessId
    // ========================================
    public void logCookieScanInitiated(String tenantId, String scanId) {
        logAudit(tenantId, null, AuditConstants.COMPONENT_COOKIE_SCAN,
                AuditConstants.ACTION_SCAN_INITIATED, AuditConstants.INITIATOR_DF,
                AuditConstants.RESOURCE_COOKIE_SCAN, scanId);
    }

    public void logCookieScanStarted(String tenantId, String scanId) {
        logAudit(tenantId, null, AuditConstants.COMPONENT_COOKIE_SCAN,
                AuditConstants.ACTION_SCAN_STARTED, AuditConstants.INITIATOR_DF,
                AuditConstants.RESOURCE_COOKIE_SCAN, scanId);
    }

    public void logCookieScanFailed(String tenantId, String scanId) {
        logAudit(tenantId, null, AuditConstants.COMPONENT_COOKIE_SCAN,
                AuditConstants.ACTION_SCAN_FAILED, AuditConstants.INITIATOR_DF,
                AuditConstants.RESOURCE_COOKIE_SCAN, scanId);
    }

    // ========================================
    // TEMPLATE METHODS (4) - businessId parameter added
    // ========================================
    public void logTemplateCreationInitiated(String tenantId, String businessId, String templateId) {
        logAudit(tenantId, businessId, AuditConstants.COMPONENT_TEMPLATE_CREATION,
                AuditConstants.ACTION_TEMPLATE_CREATION_INITIATED, AuditConstants.INITIATOR_DF,
                AuditConstants.RESOURCE_COOKIE_TEMPLATE, templateId);
    }

    public void logTemplateCreated(String tenantId, String businessId, String templateId) {
        logAudit(tenantId, businessId, AuditConstants.COMPONENT_TEMPLATE_CREATION,
                AuditConstants.ACTION_TEMPLATE_CREATED, AuditConstants.INITIATOR_DF,
                AuditConstants.RESOURCE_COOKIE_TEMPLATE, templateId);
    }

    public void logTemplateUpdateInitiated(String tenantId, String businessId, String templateId) {
        logAudit(tenantId, businessId, AuditConstants.COMPONENT_TEMPLATE_UPDATE,
                AuditConstants.ACTION_TEMPLATE_UPDATE_INITIATED, AuditConstants.INITIATOR_DF,
                AuditConstants.RESOURCE_COOKIE_TEMPLATE, templateId);
    }

    public void logNewTemplateVersionCreated(String tenantId, String businessId, String templateId) {
        logAudit(tenantId, businessId, AuditConstants.COMPONENT_TEMPLATE_UPDATE,
                AuditConstants.ACTION_NEW_TEMPLATE_VERSION_CREATED, AuditConstants.INITIATOR_DF,
                AuditConstants.RESOURCE_COOKIE_TEMPLATE, templateId);
    }

    // ========================================
    // CONSENT HANDLE METHODS (2) - businessId parameter added
    // ========================================
    public void logConsentHandleCreationInitiated(String tenantId, String businessId, String handleId) {
        logAudit(tenantId, businessId, AuditConstants.COMPONENT_CONSENT_HANDLE,
                AuditConstants.ACTION_CONSENT_HANDLE_CREATION_INITIATED, AuditConstants.INITIATOR_DF,
                AuditConstants.RESOURCE_CONSENT_HANDLE, handleId);
    }

    public void logConsentHandleCreated(String tenantId, String businessId, String handleId) {
        logAudit(tenantId, businessId, AuditConstants.COMPONENT_CONSENT_HANDLE,
                AuditConstants.ACTION_CONSENT_HANDLE_CREATED, AuditConstants.INITIATOR_DF,
                AuditConstants.RESOURCE_CONSENT_HANDLE, handleId);
    }

    // ========================================
    // CONSENT METHODS (7) - businessId parameter added
    // ========================================
    public void logConsentCreationInitiated(String tenantId, String businessId, String consentId) {
        logAudit(tenantId, businessId, AuditConstants.COMPONENT_CONSENT_CREATION,
                AuditConstants.ACTION_CONSENT_CREATION_INITIATED, AuditConstants.INITIATOR_DF,
                AuditConstants.RESOURCE_CONSENT, consentId);
    }

    public void logConsentCreated(String tenantId, String businessId, String consentId) {
        logAudit(tenantId, businessId, AuditConstants.COMPONENT_CONSENT_CREATION,
                AuditConstants.ACTION_CONSENT_CREATED, AuditConstants.INITIATOR_USER,
                AuditConstants.RESOURCE_CONSENT, consentId);
    }

    public void logConsentHandleMarkedUsed(String tenantId, String businessId, String handleId) {
        logAudit(tenantId, businessId, AuditConstants.COMPONENT_CONSENT_CREATION,
                AuditConstants.ACTION_CONSENT_HANDLE_MARKED_USED, AuditConstants.INITIATOR_DF,
                AuditConstants.RESOURCE_CONSENT, handleId);
    }

    public void logConsentUpdateInitiated(String tenantId, String businessId, String consentId) {
        logAudit(tenantId, businessId, AuditConstants.COMPONENT_CONSENT_UPDATE,
                AuditConstants.ACTION_CONSENT_UPDATE_INITIATED, AuditConstants.INITIATOR_DF,
                AuditConstants.RESOURCE_CONSENT, consentId);
    }

    public void logNewConsentVersionCreated(String tenantId, String businessId, String consentId) {
        logAudit(tenantId, businessId, AuditConstants.COMPONENT_CONSENT_UPDATE,
                AuditConstants.ACTION_NEW_CONSENT_VERSION_CREATED, AuditConstants.INITIATOR_DF,
                AuditConstants.RESOURCE_CONSENT, consentId);
    }

    public void logOldConsentVersionMarkedUpdated(String tenantId, String businessId, String consentId) {
        logAudit(tenantId, businessId, AuditConstants.COMPONENT_CONSENT_UPDATE,
                AuditConstants.ACTION_OLD_CONSENT_VERSION_MARKED_UPDATED, AuditConstants.INITIATOR_DF,
                AuditConstants.RESOURCE_CONSENT, consentId);
    }

    public void logConsentHandleMarkedUsedAfterUpdate(String tenantId, String businessId, String handleId) {
        logAudit(tenantId, businessId, AuditConstants.COMPONENT_CONSENT_UPDATE,
                AuditConstants.ACTION_CONSENT_HANDLE_MARKED_USED_AFTER_UPDATE, AuditConstants.INITIATOR_DF,
                AuditConstants.RESOURCE_CONSENT_HANDLE, handleId);
    }

    public void logConsentRevoked(String tenantId, String businessId, String consentId) {
        logAudit(tenantId, businessId, AuditConstants.COMPONENT_CONSENT_UPDATE,
                AuditConstants.ACTION_CONSENT_REVOKED, AuditConstants.INITIATOR_USER,
                AuditConstants.RESOURCE_CONSENT, consentId);
    }

    // ========================================
    // TOKEN METHODS (4) - businessId parameter added
    // ========================================
    public void logTokenVerificationInitiated(String tenantId, String businessId, String tokenId) {
        logAudit(tenantId, businessId, AuditConstants.COMPONENT_TOKEN_VERIFICATION,
                AuditConstants.ACTION_TOKEN_VERIFICATION_INITIATED, AuditConstants.INITIATOR_DP,
                AuditConstants.RESOURCE_TOKEN, tokenId);
    }

    public void logTokenSignatureVerified(String tenantId, String businessId, String tokenId) {
        logAudit(tenantId, businessId, AuditConstants.COMPONENT_TOKEN_VERIFICATION,
                AuditConstants.ACTION_TOKEN_SIGNATURE_VERIFIED, AuditConstants.INITIATOR_DF,
                AuditConstants.RESOURCE_TOKEN, tokenId);
    }

    public void logTokenValidationSuccess(String tenantId, String businessId, String tokenId) {
        logAudit(tenantId, businessId, AuditConstants.COMPONENT_TOKEN_VERIFICATION,
                AuditConstants.ACTION_TOKEN_VALIDATION_SUCCESS, AuditConstants.INITIATOR_DF,
                AuditConstants.RESOURCE_TOKEN, tokenId);
    }

    public void logTokenValidationFailed(String tenantId, String businessId, String tokenId) {
        logAudit(tenantId, businessId, AuditConstants.COMPONENT_TOKEN_VERIFICATION,
                AuditConstants.ACTION_TOKEN_VALIDATION_FAILED, AuditConstants.INITIATOR_DF,
                AuditConstants.RESOURCE_TOKEN, tokenId);
    }
}