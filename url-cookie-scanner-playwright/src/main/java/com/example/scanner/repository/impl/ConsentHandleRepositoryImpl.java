package com.example.scanner.repository.impl;

import com.example.scanner.config.MultiTenantMongoConfig;
import com.example.scanner.config.TenantContext;
import com.example.scanner.entity.ConsentHandle;
import com.example.scanner.repository.ConsentHandleRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
@Slf4j
public class ConsentHandleRepositoryImpl implements ConsentHandleRepository {

    private final MultiTenantMongoConfig mongoConfig;

    @Override
    public ConsentHandle save(ConsentHandle consentHandle, String tenantId) {
        if (tenantId == null) {
            throw new IllegalStateException("Tenant context is not set");
        }

        TenantContext.setCurrentTenant(tenantId);
        try {
            MongoTemplate tenantMongoTemplate = mongoConfig.getMongoTemplateForTenant(tenantId);
            return tenantMongoTemplate.save(consentHandle);
        } finally {
            TenantContext.clear();
        }
    }

    @Override
    public ConsentHandle getByConsentHandleId(String consentHandleId, String tenantId) {
        if (tenantId == null) {
            throw new IllegalStateException("Tenant context is not set");
        }

        TenantContext.setCurrentTenant(tenantId);
        try {
            MongoTemplate tenantMongoTemplate = mongoConfig.getMongoTemplateForTenant(tenantId);
            Criteria criteria = new Criteria();
            criteria.and("consentHandleId").is(consentHandleId);
            Query query = new Query();
            query.addCriteria(criteria);
            query.fields().exclude("_id");
            return tenantMongoTemplate.findOne(query, ConsentHandle.class);
        } finally {
            TenantContext.clear();
        }
    }

}