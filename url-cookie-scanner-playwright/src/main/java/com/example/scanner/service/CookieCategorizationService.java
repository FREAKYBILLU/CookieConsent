package com.example.scanner.service;

import com.example.scanner.dto.CookieCategorizationRequest;
import com.example.scanner.dto.CookieCategorizationResponse;
import com.example.scanner.exception.CookieCategorizationException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Recover;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Service
public class CookieCategorizationService {

    private static final Logger log = LoggerFactory.getLogger(CookieCategorizationService.class);

    @Value("${cookie.categorization.api.url}")
    private String categorizationApiUrl;

    @Value("${cookie.categorization.cache.enabled}")
    private boolean cacheEnabled;

    @Value("${cookie.categorization.cache.ttl.minutes:60}")
    private long cacheTtlMinutes;

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    // Simple in-memory cache
    private final Map<String, CacheEntry> categorizationCache = new ConcurrentHashMap<>();

    public CookieCategorizationService(RestTemplate restTemplate, ObjectMapper objectMapper) {
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
    }

    /**
     * Categorize cookies with retry mechanism
     */
    public Map<String, CookieCategorizationResponse> categorizeCookies(List<String> cookieNames) {
        if (cookieNames == null || cookieNames.isEmpty()) {
            log.debug("No cookie names provided for categorization");
            return Collections.emptyMap();
        }

        try {
            log.info("Starting categorization for {} cookies", cookieNames.size());

            // Check cache first
            Map<String, CookieCategorizationResponse> cachedResults = getCachedResults(cookieNames);
            List<String> uncachedCookies = cookieNames.stream()
                    .filter(name -> !cachedResults.containsKey(name))
                    .collect(Collectors.toList());

            Map<String, CookieCategorizationResponse> apiResults = Collections.emptyMap();

            if (!uncachedCookies.isEmpty()) {
                log.debug("Fetching categorization for {} uncached cookies", uncachedCookies.size());
                apiResults = callCategorizationApiWithRetry(uncachedCookies);

                // Cache the new results
                if (cacheEnabled && !apiResults.isEmpty()) {
                    cacheResults(apiResults);
                }
            }

            // Combine cached and API results
            Map<String, CookieCategorizationResponse> allResults = new ConcurrentHashMap<>(cachedResults);
            allResults.putAll(apiResults);

            log.info("Successfully categorized {} out of {} cookies ({} from cache, {} from API)",
                    allResults.size(), cookieNames.size(), cachedResults.size(), apiResults.size());

            return allResults;

        } catch (Exception e) {
            log.error("Error during cookie categorization: {}", e.getMessage(), e);
            return Collections.emptyMap(); // Return empty map instead of throwing exception
        }
    }

    /**
     * Call categorization API with automatic retry
     */
    @Retryable(
            value = {ResourceAccessException.class, HttpServerErrorException.class, RestClientException.class},
            maxAttempts = 3,
            backoff = @Backoff(delay = 1000, multiplier = 2.0, maxDelay = 10000)
    )
    public Map<String, CookieCategorizationResponse> callCategorizationApiWithRetry(List<String> cookieNames) {
        log.debug("Calling categorization API for cookies: {}", cookieNames);

        try {
            // Prepare request
            CookieCategorizationRequest request = new CookieCategorizationRequest(cookieNames);
            log.info("Testing {}", request);

            // Set headers
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("User-Agent", "CookieScanner/1.0");

            HttpEntity<CookieCategorizationRequest> requestEntity = new HttpEntity<>(request, headers);

            // Make API call
            ResponseEntity<String> response = restTemplate.exchange(
                    categorizationApiUrl,
                    HttpMethod.POST,
                    requestEntity,
                    String.class
            );

            return parseApiResponse(response, cookieNames);

        } catch (Exception e) {
            log.error("API call failed: {}", e.getMessage());
            throw e; // Let @Retryable handle this
        }
    }

    /**
     * Recovery method - called when all retry attempts fail
     */
    @Recover
    public Map<String, CookieCategorizationResponse> recoverFromApiFailure(Exception ex, List<String> cookieNames) {
        log.error("All retry attempts failed for cookie categorization. Returning empty results. Error: {}", ex.getMessage());

        // Return empty map or default categorizations
        return Collections.emptyMap();
    }

    /**
     * Get categorization for a single cookie
     */
    public CookieCategorizationResponse categorizeSingleCookie(String cookieName) {
        if (cookieName == null || cookieName.trim().isEmpty()) {
            return null;
        }

        Map<String, CookieCategorizationResponse> result = categorizeCookies(List.of(cookieName.trim()));
        return result.get(cookieName.trim());
    }

    // Helper methods remain the same
    private Map<String, CookieCategorizationResponse> getCachedResults(List<String> cookieNames) {
        if (!cacheEnabled) {
            return Collections.emptyMap();
        }

        cleanExpiredEntries();

        Map<String, CookieCategorizationResponse> cachedResults = new ConcurrentHashMap<>();
        Instant now = Instant.now();

        for (String cookieName : cookieNames) {
            CacheEntry entry = categorizationCache.get(cookieName);
            if (entry != null && entry.isValid(now)) {
                cachedResults.put(cookieName, entry.response);
            }
        }

        return cachedResults;
    }

    private Map<String, CookieCategorizationResponse> parseApiResponse(ResponseEntity<String> response, List<String> requestedCookies) {
        try {
            if (!response.getStatusCode().is2xxSuccessful()) {
                throw new RuntimeException("API returned status: " + response.getStatusCode());
            }

            String responseBody = response.getBody();
            log.info("Testing {}", responseBody);
            if (responseBody == null || responseBody.trim().isEmpty()) {
                throw new RuntimeException("Empty response from API");
            }

            List<CookieCategorizationResponse> responses = objectMapper.readValue(
                    responseBody,
                    new TypeReference<List<CookieCategorizationResponse>>() {}
            );
            log.info("Testing {}", responses);

            return responses.stream()
                    .filter(resp -> resp != null && resp.getName() != null)
                    .collect(Collectors.toMap(
                            CookieCategorizationResponse::getName,
                            resp -> resp,
                            (existing, replacement) -> existing
                    ));

        } catch (Exception e) {
            log.error("Error parsing API response: {}", e.getMessage());
            throw new RuntimeException("Failed to parse response", e);
        }
    }

    private void cacheResults(Map<String, CookieCategorizationResponse> results) {
        Instant expiryTime = Instant.now().plus(Duration.ofMinutes(cacheTtlMinutes));

        for (Map.Entry<String, CookieCategorizationResponse> entry : results.entrySet()) {
            CacheEntry cacheEntry = new CacheEntry(entry.getValue(), expiryTime);
            categorizationCache.put(entry.getKey(), cacheEntry);
        }
    }

    private void cleanExpiredEntries() {
        Instant now = Instant.now();
        categorizationCache.entrySet().removeIf(entry -> !entry.getValue().isValid(now));
    }

    // Cache entry class
    private static class CacheEntry {
        final CookieCategorizationResponse response;
        final Instant expiryTime;

        CacheEntry(CookieCategorizationResponse response, Instant expiryTime) {
            this.response = response;
            this.expiryTime = expiryTime;
        }

        boolean isValid(Instant now) {
            return now.isBefore(expiryTime);
        }
    }
}