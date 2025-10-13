package com.example.scanner.repository.impl;

import com.example.scanner.config.MultiTenantMongoConfig;
import com.example.scanner.config.TenantContext;
import com.example.scanner.dto.CustomerIdentifiers;
import com.example.scanner.entity.Consent;
import com.example.scanner.enums.VersionStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
@RequiredArgsConstructor
@Slf4j
public class ConsentRepositoryImpl {

    private final MultiTenantMongoConfig mongoConfig;

    public Consent save(Consent consent, String tenantId) {
        if (tenantId == null) {
            throw new IllegalStateException("Tenant context is not set");
        }

        TenantContext.setCurrentTenant(tenantId);
        try {
            MongoTemplate tenantMongoTemplate = mongoConfig.getMongoTemplateForTenant(tenantId);
            return tenantMongoTemplate.save(consent);
        } finally {
            TenantContext.clear();
        }
    }

    public Consent existByTemplateIdAndTemplateVersionAndCustomerIdentifiers(
            String templateId, Integer templateVersion, CustomerIdentifiers customerIdentifiers, String tenantId,
            String consentHandleId) {

        if (tenantId == null) {
            throw new IllegalStateException("Tenant context is not set");
        }

        TenantContext.setCurrentTenant(tenantId);
        try {
            MongoTemplate tenantMongoTemplate = mongoConfig.getMongoTemplateForTenant(tenantId);

            Criteria criteria = new Criteria();
            criteria.and("templateId").is(templateId)
                    .and("templateVersion").is(templateVersion)
                    .and("customerIdentifiers.type").is(customerIdentifiers.getType())
                    .and("customerIdentifiers.value").is(customerIdentifiers.getValue())
                    .and("consentHandleId").is(consentHandleId);

            Query query = new Query(criteria);
            return tenantMongoTemplate.findOne(query, Consent.class);
        } finally {
            TenantContext.clear();
        }
    }

    public Consent findActiveByConsentId(String consentId, String tenantId) {
        if (tenantId == null) {
            throw new IllegalStateException("Tenant context is not set");
        }

        TenantContext.setCurrentTenant(tenantId);
        try {
            MongoTemplate tenantMongoTemplate = mongoConfig.getMongoTemplateForTenant(tenantId);

            Query query = new Query(Criteria.where("consentId").is(consentId)
                    .and("consentStatus").is(VersionStatus.ACTIVE));

            return tenantMongoTemplate.findOne(query, Consent.class);
        } finally {
            TenantContext.clear();
        }
    }

    public List<Consent> findAllVersionsByConsentId(String consentId, String tenantId) {
        if (tenantId == null) {
            throw new IllegalStateException("Tenant context is not set");
        }

        TenantContext.setCurrentTenant(tenantId);
        try {
            MongoTemplate tenantMongoTemplate = mongoConfig.getMongoTemplateForTenant(tenantId);

            Query query = new Query(Criteria.where("consentId").is(consentId))
                    .with(Sort.by(Sort.Direction.DESC, "version"));

            return tenantMongoTemplate.find(query, Consent.class);
        } finally {
            TenantContext.clear();
        }
    }


    public Optional<Consent> findByConsentIdAndVersion(String consentId, Integer version, String tenantId) {
        if (tenantId == null) {
            throw new IllegalStateException("Tenant context is not set");
        }

        TenantContext.setCurrentTenant(tenantId);
        try {
            MongoTemplate tenantMongoTemplate = mongoConfig.getMongoTemplateForTenant(tenantId);

            Query query = new Query(Criteria.where("consentId").is(consentId)
                    .and("version").is(version));

            Consent consent = tenantMongoTemplate.findOne(query, Consent.class);
            return Optional.ofNullable(consent);
        } finally {
            TenantContext.clear();
        }
    }
}