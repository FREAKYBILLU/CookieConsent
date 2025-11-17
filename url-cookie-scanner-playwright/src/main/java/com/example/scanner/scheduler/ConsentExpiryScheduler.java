package com.example.scanner.scheduler;

import com.example.scanner.config.MultiTenantMongoConfig;
import com.example.scanner.entity.CookieConsent;
import com.example.scanner.enums.Status;
import com.example.scanner.enums.VersionStatus;
import com.mongodb.client.MongoClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Scheduler to mark expired consents as EXPIRED
 * Runs based on configured cron expression to check ACTIVE consents that have crossed their endDate
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ConsentExpiryScheduler {

    private final MultiTenantMongoConfig mongoConfig;
    private final MongoClient mongoClient;

    @Value("${multi-tenant.tenant-database-prefix}")
    private String tenantDatabasePrefix;

    /**
     * Scheduled task that runs based on cron expression from application.properties
     * Default: Once a day at midnight (0 0 0 * * *)
     * Finds all ACTIVE consents that have expired and marks them as EXPIRED
     */
    @Scheduled(cron = "${scheduler.consent-expiry.cron:0 0 0 * * *}")
    public void markExpiredConsents() {
        log.info("Starting consent expiry scheduler at {}", Instant.now());

        try {
            // Get all tenant databases
            List<String> tenantDatabases = getAllTenantDatabases();

            int totalExpired = 0;

            for (String tenantDb : tenantDatabases) {
                try {
                    String tenantId = extractTenantIdFromDatabase(tenantDb);
                    int expiredCount = markExpiredConsentsForTenant(tenantId);
                    totalExpired += expiredCount;

                    if (expiredCount > 0) {
                        log.info("Marked {} consents as EXPIRED for tenant: {}", expiredCount, tenantId);
                    }
                } catch (Exception e) {
                    log.error("Error processing tenant database: {}", tenantDb, e);
                }
            }

            log.info("Consent expiry scheduler completed. Total consents marked as EXPIRED: {}", totalExpired);

        } catch (Exception e) {
            log.error("Error in consent expiry scheduler", e);
        }
    }

    /**
     * Mark expired consents for a specific tenant
     */
    private int markExpiredConsentsForTenant(String tenantId) {
        MongoTemplate mongoTemplate = mongoConfig.getMongoTemplateForTenant(tenantId);
        LocalDateTime now = LocalDateTime.now();

        // Find all ACTIVE consents (consentStatus = ACTIVE) where status is ACTIVE and endDate < current time
        Query query = new Query();
        query.addCriteria(
                Criteria.where("consentStatus").is(VersionStatus.ACTIVE)
                        .and("status").is(Status.ACTIVE)
                        .and("endDate").lt(now)
        );

        // Update to EXPIRED status
        Update update = new Update();
        update.set("status", Status.EXPIRED);
        update.set("updatedAt", Instant.now());

        // Execute bulk update
        long modifiedCount = mongoTemplate.updateMulti(query, update, CookieConsent.class).getModifiedCount();

        return (int) modifiedCount;
    }

    /**
     * Get all tenant database names from MongoDB
     */
    private List<String> getAllTenantDatabases() {
        List<String> tenantDbs = new ArrayList<>();

        for (String dbName : mongoClient.listDatabaseNames()) {
            if (dbName.startsWith(tenantDatabasePrefix)) {
                tenantDbs.add(dbName);
            }
        }

        return tenantDbs;
    }

    /**
     * Extract tenant ID from database name
     * Example: tenant_db_ABC123 -> ABC123
     */
    private String extractTenantIdFromDatabase(String databaseName) {
        return databaseName.replace(tenantDatabasePrefix, "");
    }
}