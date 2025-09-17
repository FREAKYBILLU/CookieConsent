package com.example.scanner.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ApiConfig {

    @Value("${api.context.path:/}")  // Default to / for now
    private String apiContextPath;

    @Value("${api.versioning.enabled:false}")  // Disabled for now
    private boolean versioningEnabled;

    // Getters for future use
    public String getApiContextPath() {
        return versioningEnabled ? apiContextPath : "/";
    }
}