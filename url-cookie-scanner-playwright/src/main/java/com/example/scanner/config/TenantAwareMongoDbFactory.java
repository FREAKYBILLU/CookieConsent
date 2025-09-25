package com.example.scanner.config;

import com.example.scanner.util.TenantContextHolder;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.MongoIterable;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.mongodb.core.SimpleMongoClientDatabaseFactory;

@Slf4j
public class TenantAwareMongoDbFactory extends SimpleMongoClientDatabaseFactory {

    private final String sharedDatabase;
    private final String tenantDatabasePrefix;

    public TenantAwareMongoDbFactory(MongoClient mongoClient, String sharedDatabase, String tenantDatabasePrefix) {
        super(mongoClient, sharedDatabase); // Default database for SimpleMongoClientDatabaseFactory
        this.sharedDatabase = sharedDatabase;
        this.tenantDatabasePrefix = tenantDatabasePrefix;
    }

    @Override
    public MongoDatabase getMongoDatabase() {
        try {
            String tenantId = TenantContextHolder.getTenant();
            String dbName = tenantDatabasePrefix + tenantId;

            if (!databaseExists(dbName)) {
                throw new IllegalStateException("Database does not exist for tenant: " + tenantId);
            }

            log.debug("TenantAwareMongoDbFactory: Retrieved tenantId: {}, Resolved DB Name: {}", tenantId, dbName);
            return super.getMongoDatabase(dbName);
        } catch (IllegalStateException e) {
            log.warn("TenantAwareMongoDbFactory: No tenant context found or database doesn't exist, falling back to shared database: {}", sharedDatabase);
            return super.getMongoDatabase(sharedDatabase);
        }
    }

    private boolean databaseExists(String databaseName) {
        try {
            MongoIterable<String> databaseNames = getMongoClient().listDatabaseNames();
            for (String name : databaseNames) {
                if (name.equals(databaseName)) {
                    return true;
                }
            }
            return false;
        } catch (Exception e) {
            log.error("Error checking database existence for: {}", databaseName, e);
            return false;
        }
    }
}