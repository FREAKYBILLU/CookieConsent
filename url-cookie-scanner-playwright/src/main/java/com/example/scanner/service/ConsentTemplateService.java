package com.example.scanner.service;
import com.example.scanner.config.MultiTenantMongoConfig;
import com.example.scanner.config.TenantContext;
import com.example.scanner.entity.ConsentTemplate;
import com.example.scanner.repository.ConsentTemplateRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ConsentTemplateService {

    @Autowired
    private ConsentTemplateRepository repository;

    private final MultiTenantMongoConfig mongoConfig;

    /**
     * Get specific template by tenant ID and scan ID combination
     */
    public Optional<ConsentTemplate> getTemplateByTenantAndScanId(String tenantId, String scanId) {
        // Set tenant context for database routing
        TenantContext.setCurrentTenant(tenantId);
        MongoTemplate tenantMongoTemplate = mongoConfig.getMongoTemplateForTenant(tenantId);

        try {
            // Query the tenant-specific database using MongoTemplate
            Query query = new Query(Criteria.where("scanId").is(scanId));
            ConsentTemplate template = tenantMongoTemplate.findOne(query, ConsentTemplate.class);
            return Optional.ofNullable(template);
        } finally {
            // Clear tenant context after operation
            TenantContext.clear();
        }
    }

    public List<ConsentTemplate> getTemplatesByTenantId(String tenantId) {
        // Set tenant context for database routing
        TenantContext.setCurrentTenant(tenantId);
        MongoTemplate tenantMongoTemplate = mongoConfig.getMongoTemplateForTenant(tenantId);

        try {
            return tenantMongoTemplate.findAll(ConsentTemplate.class);
        } finally {
            TenantContext.clear();
        }
    }

    public ConsentTemplate createTemplate(String tenantId, ConsentTemplate template) {
        template.setScanId(UUID.randomUUID().toString());
        template.setCreatedAt(Instant.now());
        template.setUpdatedAt(Instant.now());

        // Set tenant context for database routing
        TenantContext.setCurrentTenant(tenantId);
        MongoTemplate tenantMongoTemplate = mongoConfig.getMongoTemplateForTenant(tenantId);
        try {
            return tenantMongoTemplate.save(template);
        } finally {
            TenantContext.clear();
        }
    }
}
