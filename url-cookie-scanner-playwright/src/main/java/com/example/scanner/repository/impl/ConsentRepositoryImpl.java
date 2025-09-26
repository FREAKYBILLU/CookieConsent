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

import java.time.LocalDateTime;
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
            String templateId, Integer templateVersion, CustomerIdentifiers customerIdentifiers, String tenantId) {

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
                    .and("customerIdentifiers.value").is(customerIdentifiers.getValue());

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

    public List<Consent> findActiveConsentsByCustomer(String customerValue, String tenantId) {
        if (tenantId == null) {
            throw new IllegalStateException("Tenant context is not set");
        }

        TenantContext.setCurrentTenant(tenantId);
        try {
            MongoTemplate tenantMongoTemplate = mongoConfig.getMongoTemplateForTenant(tenantId);

            Query query = new Query(Criteria.where("customerIdentifiers.value").is(customerValue)
                    .and("consentStatus").is(VersionStatus.ACTIVE));

            return tenantMongoTemplate.find(query, Consent.class);
        } finally {
            TenantContext.clear();
        }
    }

    public List<Consent> findAllConsentsByCustomerOrderByConsentIdAscVersionDesc(String customerValue, String tenantId) {
        if (tenantId == null) {
            throw new IllegalStateException("Tenant context is not set");
        }

        TenantContext.setCurrentTenant(tenantId);
        try {
            MongoTemplate tenantMongoTemplate = mongoConfig.getMongoTemplateForTenant(tenantId);

            Query query = new Query(Criteria.where("customerIdentifiers.value").is(customerValue))
                    .with(Sort.by(Sort.Direction.ASC, "consentId")
                            .and(Sort.by(Sort.Direction.DESC, "version")));

            return tenantMongoTemplate.find(query, Consent.class);
        } finally {
            TenantContext.clear();
        }
    }

    public List<Consent> findActiveConsentsByBusinessId(String businessId, String tenantId) {
        if (tenantId == null) {
            throw new IllegalStateException("Tenant context is not set");
        }

        TenantContext.setCurrentTenant(tenantId);
        try {
            MongoTemplate tenantMongoTemplate = mongoConfig.getMongoTemplateForTenant(tenantId);

            Query query = new Query(Criteria.where("businessId").is(businessId)
                    .and("consentStatus").is(VersionStatus.ACTIVE));

            return tenantMongoTemplate.find(query, Consent.class);
        } finally {
            TenantContext.clear();
        }
    }

    public List<Consent> findByTemplateIdAndVersion(String templateId, Integer templateVersion, String tenantId) {
        if (tenantId == null) {
            throw new IllegalStateException("Tenant context is not set");
        }

        TenantContext.setCurrentTenant(tenantId);
        try {
            MongoTemplate tenantMongoTemplate = mongoConfig.getMongoTemplateForTenant(tenantId);

            Query query = new Query(Criteria.where("templateId").is(templateId)
                    .and("templateVersion").is(templateVersion));

            return tenantMongoTemplate.find(query, Consent.class);
        } finally {
            TenantContext.clear();
        }
    }

    public List<Consent> findActiveConsentsByTemplateId(String templateId, String tenantId) {
        if (tenantId == null) {
            throw new IllegalStateException("Tenant context is not set");
        }

        TenantContext.setCurrentTenant(tenantId);
        try {
            MongoTemplate tenantMongoTemplate = mongoConfig.getMongoTemplateForTenant(tenantId);

            Query query = new Query(Criteria.where("templateId").is(templateId)
                    .and("consentStatus").is(VersionStatus.ACTIVE));

            return tenantMongoTemplate.find(query, Consent.class);
        } finally {
            TenantContext.clear();
        }
    }

    public boolean existsByConsentId(String consentId, String tenantId) {
        if (tenantId == null) {
            throw new IllegalStateException("Tenant context is not set");
        }

        TenantContext.setCurrentTenant(tenantId);
        try {
            MongoTemplate tenantMongoTemplate = mongoConfig.getMongoTemplateForTenant(tenantId);

            Query query = new Query(Criteria.where("consentId").is(consentId));

            return tenantMongoTemplate.exists(query, Consent.class);
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

    public Optional<Consent> findMaxVersionByConsentId(String consentId, String tenantId) {
        if (tenantId == null) {
            throw new IllegalStateException("Tenant context is not set");
        }

        TenantContext.setCurrentTenant(tenantId);
        try {
            MongoTemplate tenantMongoTemplate = mongoConfig.getMongoTemplateForTenant(tenantId);

            Query query = new Query(Criteria.where("consentId").is(consentId))
                    .with(Sort.by(Sort.Direction.DESC, "version"))
                    .limit(1);

            Consent consent = tenantMongoTemplate.findOne(query, Consent.class);
            return Optional.ofNullable(consent);
        } finally {
            TenantContext.clear();
        }
    }

    public long countVersionsByConsentId(String consentId, String tenantId) {
        if (tenantId == null) {
            throw new IllegalStateException("Tenant context is not set");
        }

        TenantContext.setCurrentTenant(tenantId);
        try {
            MongoTemplate tenantMongoTemplate = mongoConfig.getMongoTemplateForTenant(tenantId);

            Query query = new Query(Criteria.where("consentId").is(consentId));

            return tenantMongoTemplate.count(query, Consent.class);
        } finally {
            TenantContext.clear();
        }
    }

    public Optional<Consent> findByConsentHandleId(String consentHandleId, String tenantId) {
        if (tenantId == null) {
            throw new IllegalStateException("Tenant context is not set");
        }

        TenantContext.setCurrentTenant(tenantId);
        try {
            MongoTemplate tenantMongoTemplate = mongoConfig.getMongoTemplateForTenant(tenantId);

            Query query = new Query(Criteria.where("consentHandleId").is(consentHandleId));

            Consent consent = tenantMongoTemplate.findOne(query, Consent.class);
            return Optional.ofNullable(consent);
        } finally {
            TenantContext.clear();
        }
    }

    public List<Consent> findExpiredActiveConsents(LocalDateTime currentTime, String tenantId) {
        if (tenantId == null) {
            throw new IllegalStateException("Tenant context is not set");
        }

        TenantContext.setCurrentTenant(tenantId);
        try {
            MongoTemplate tenantMongoTemplate = mongoConfig.getMongoTemplateForTenant(tenantId);

            Query query = new Query(Criteria.where("consentStatus").is(VersionStatus.ACTIVE)
                    .and("endDate").lt(currentTime));

            return tenantMongoTemplate.find(query, Consent.class);
        } finally {
            TenantContext.clear();
        }
    }
}