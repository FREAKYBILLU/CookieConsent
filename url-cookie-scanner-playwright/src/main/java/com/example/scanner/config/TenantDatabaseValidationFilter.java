package com.example.scanner.config;

import com.example.scanner.constants.ErrorCodes;
import com.example.scanner.dto.response.ErrorResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoIterable;

import java.io.IOException;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;

@Component
@Order(2) // After PathTraversal filter, before InvalidCharacter filter
@Slf4j
public class TenantDatabaseValidationFilter implements Filter {

    private final MongoClient mongoClient;
    private final ObjectMapper objectMapper;

    @Value("${multi-tenant.tenant-database-prefix}")
    private String tenantDatabasePrefix;

    // Skip validation for these paths
    private static final List<String> SKIP_PATHS = Arrays.asList(
            "/health", "/metrics", "/error", "/swagger-ui", "/api-docs"
    );

    public TenantDatabaseValidationFilter(MongoClient mongoClient, ObjectMapper objectMapper) {
        this.mongoClient = mongoClient;
        this.objectMapper = objectMapper;
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {

        if (request instanceof HttpServletRequest && response instanceof HttpServletResponse) {
            HttpServletRequest httpRequest = (HttpServletRequest) request;
            HttpServletResponse httpResponse = (HttpServletResponse) response;

            String requestURI = httpRequest.getRequestURI();

            // Skip validation for health/metrics endpoints
            if (shouldSkipValidation(requestURI)) {
                chain.doFilter(request, response);
                return;
            }

            String tenantId = httpRequest.getHeader("X-Tenant-ID");

            // If no tenant header, let it pass (other validation will catch it)
            if (tenantId == null || tenantId.trim().isEmpty()) {
                chain.doFilter(request, response);
                return;
            }

            // Validate tenant database exists
            String databaseName = tenantDatabasePrefix + tenantId.trim();

            if (!databaseExists(databaseName)) {
                log.warn("TENANT DATABASE ERROR: Database '{}' does not exist for tenant '{}'",
                        databaseName, tenantId);
                handleDatabaseNotExists(httpRequest, httpResponse, tenantId, databaseName);
                return;
            }

            log.debug("Tenant database validation passed for: {}", tenantId);
        }

        chain.doFilter(request, response);
    }

    private boolean shouldSkipValidation(String requestURI) {
        return SKIP_PATHS.stream().anyMatch(requestURI::startsWith);
    }

    private boolean databaseExists(String databaseName) {
        try {
            MongoIterable<String> databaseNames = mongoClient.listDatabaseNames();
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

    private void handleDatabaseNotExists(HttpServletRequest request, HttpServletResponse response,
                                         String tenantId, String databaseName) throws IOException {

        ErrorResponse errorResponse = new ErrorResponse(
                ErrorCodes.VALIDATION_ERROR,
                "Invalid tenant ID - database does not exist",
                String.format("Database '%s' does not exist for tenant '%s'. Please verify the tenant ID in X-Tenant-ID header.",
                        databaseName, tenantId),
                Instant.now(),
                request.getRequestURI()
        );

        // Force JSON response
        response.setStatus(HttpStatus.BAD_REQUEST.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");

        // Add security headers
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setHeader("X-Frame-Options", "DENY");
        response.setHeader("X-XSS-Protection", "1; mode=block");

        String jsonResponse = objectMapper.writeValueAsString(errorResponse);
        response.getWriter().write(jsonResponse);
        response.getWriter().flush();
    }
}