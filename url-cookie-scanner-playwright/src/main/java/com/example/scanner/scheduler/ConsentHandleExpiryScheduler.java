package com.example.scanner.scheduler;

import com.example.scanner.config.MultiTenantMongoConfig;
import com.example.scanner.entity.CookieConsentHandle;
import com.example.scanner.enums.ConsentHandleStatus;
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
import java.util.ArrayList;
import java.util.List;

/**
 * Scheduler to mark expired consent handles as EXPIRED
 * Runs based on configured cron expression to check PENDING consent handles that have crossed their expiresAt time
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ConsentHandleExpiryScheduler {

    private final MultiTenantMongoConfig mongoConfig;
    private final MongoClient mongoClient;

    @Value("${multi-tenant.tenant-database-prefix}")
    private String tenantDatabasePrefix;

    /**
     * Scheduled task that runs based on cron expression from application.properties
     * Default: Every minute (0 * * * * *)
     * Finds all PENDING consent handles that have expired and marks them as EXPIRED
     */
    @Scheduled(cron = "${scheduler.consent-handle-expiry.cron:0 * * * * *}")
    public void markExpiredConsentHandles() {
        log.info("Starting consent handle expiry scheduler at {}", Instant.now());

        try {
            // Get all tenant databases
            List<String> tenantDatabases = getAllTenantDatabases();

            int totalExpired = 0;

            for (String tenantDb : tenantDatabases) {
                try {
                    String tenantId = extractTenantIdFromDatabase(tenantDb);
                    int expiredCount = markExpiredHandlesForTenant(tenantId);
                    totalExpired += expiredCount;

                    if (expiredCount > 0) {
                        log.info("Marked {} consent handles as EXPIRED for tenant: {}", expiredCount, tenantId);
                    }
                } catch (Exception e) {
                    log.error("Error processing tenant database: {}", tenantDb, e);
                }
            }

            log.info("Consent handle expiry scheduler completed. Total handles marked as EXPIRED: {}", totalExpired);

        } catch (Exception e) {
            log.error("Error in consent handle expiry scheduler", e);
        }
    }

    /**
     * Mark expired consent handles for a specific tenant
     */
    private int markExpiredHandlesForTenant(String tenantId) {
        MongoTemplate mongoTemplate = mongoConfig.getMongoTemplateForTenant(tenantId);
        Instant now = Instant.now();

        // Find all PENDING handles where expiresAt < current time
        Query query = new Query();
        query.addCriteria(
                Criteria.where("status").is(ConsentHandleStatus.PENDING)
                        .and("expiresAt").lt(now)
        );

        // Update to EXPIRED status
        Update update = new Update();
        update.set("status", ConsentHandleStatus.REQ_EXPIRED);
        update.set("updatedAt", now);

        // Execute bulk update
        long modifiedCount = mongoTemplate.updateMulti(query, update, CookieConsentHandle.class).getModifiedCount();

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