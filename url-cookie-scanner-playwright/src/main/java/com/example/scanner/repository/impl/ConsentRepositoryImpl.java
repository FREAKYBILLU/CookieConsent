package com.example.scanner.repository.impl;

import com.example.scanner.config.MultiTenantMongoConfig;
import com.example.scanner.config.TenantContext;
import com.example.scanner.dto.CustomerIdentifiers;
import com.example.scanner.entity.Consent;
import com.example.scanner.repository.ConsentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
@Slf4j
public class ConsentRepositoryImpl implements ConsentRepository {

    private final MultiTenantMongoConfig mongoConfig;

    @Override
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

    @Override
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
}