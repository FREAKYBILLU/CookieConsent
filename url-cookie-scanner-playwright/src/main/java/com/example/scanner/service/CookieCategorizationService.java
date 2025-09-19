package com.example.scanner.service;

import com.example.scanner.constants.ErrorCodes;
import com.example.scanner.dto.CookieCategorizationRequest;
import com.example.scanner.dto.CookieCategorizationResponse;
import com.example.scanner.exception.CookieCategorizationException;
import com.example.scanner.exception.UrlValidationException;
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

    @Value("${cookie.categorization.retry.maxAttempts:3}")
    private int maxRetryAttempts;

    private final RestTemplate restTemplate; // Injected from RestTemplateConfig
    private final ObjectMapper objectMapper;

    // Simple in-memory cache
    private final Map<String, CacheEntry> categorizationCache = new ConcurrentHashMap<>();

    public CookieCategorizationService(RestTemplate restTemplate, ObjectMapper objectMapper) {
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
        log.info("CookieCategorizationService initialized with cache enabled: {}, TTL: {} minutes",
                cacheEnabled, cacheTtlMinutes);
    }

    /**
     * Categorize cookies with retry mechanism
     */
    public Map<String, CookieCategorizationResponse> categorizeCookies(List<String> cookieNames) throws CookieCategorizationException {
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
            log.error("Cookie categorization service failed: {}", e.getMessage(), e);
            throw new CookieCategorizationException("Cookie categorization service is unavailable: " + e.getMessage());
        }
    }

    /**
     * Call categorization API with automatic retry
     */
    @Retryable(
            value = {ResourceAccessException.class, HttpServerErrorException.class, RestClientException.class},
            maxAttemptsExpression = "#{${cookie.categorization.retry.maxAttempts:3}}",
            backoff = @Backoff(
                    delayExpression = "#{${cookie.categorization.retry.delay:1000}}",
                    multiplierExpression = "#{${cookie.categorization.retry.multiplier:2.0}}",
                    maxDelayExpression = "#{${cookie.categorization.retry.maxDelay:10000}}"
            )
    )
    public Map<String, CookieCategorizationResponse> callCategorizationApiWithRetry(List<String> cookieNames) throws CookieCategorizationException {
        log.debug("Calling categorization API for cookies: {}", cookieNames);

        Instant startTime = Instant.now();

        try {
            // Prepare request
            CookieCategorizationRequest request = new CookieCategorizationRequest(cookieNames);
            log.debug("API Request payload: {}", request);

            // Set headers
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("User-Agent", "CookieScanner/1.0");
            headers.set("Accept", "application/json");

            HttpEntity<CookieCategorizationRequest> requestEntity = new HttpEntity<>(request, headers);

            // Make API call with injected RestTemplate (has timeout configured)
            ResponseEntity<String> response = restTemplate.exchange(
                    categorizationApiUrl,
                    HttpMethod.POST,
                    requestEntity,
                    String.class
            );

            Duration callDuration = Duration.between(startTime, Instant.now());
            log.info("API call completed successfully in {}ms", callDuration.toMillis());

            return parseApiResponse(response, cookieNames);

        } catch (ResourceAccessException e) {
            Duration callDuration = Duration.between(startTime, Instant.now());
            log.error("API TIMEOUT after {}ms. Target: {}. Error: {}",
                    callDuration.toMillis(), categorizationApiUrl, e.getMessage());

            if (e.getMessage() != null && e.getMessage().toLowerCase().contains("timeout")) {
                log.error("CONFIRMED: Read timeout occurred. External API is taking too long to respond.");
            }
            throw e;

        } catch (HttpServerErrorException e) {
            Duration callDuration = Duration.between(startTime, Instant.now());
            log.error("API returned server error after {}ms. Status: {}, Response: {}",
                    callDuration.toMillis(), e.getStatusCode(), e.getResponseBodyAsString());
            throw e;

        } catch (Exception e) {
            Duration callDuration = Duration.between(startTime, Instant.now());
            log.error("API call failed after {}ms: {} - {}",
                    callDuration.toMillis(), e.getClass().getSimpleName(), e.getMessage());
            throw e;
        }
    }

    /**
     * Recovery method for retry failures - provides graceful degradation
     */
    @Recover
    public Map<String, CookieCategorizationResponse> recoverFromApiFailure(Exception e, List<String> cookieNames) {
        log.warn("Cookie categorization API completely failed after {} retries. Cookies: {}. Final error: {}",
                maxRetryAttempts, cookieNames.size(), e.getMessage());

        log.warn("Service will continue without categorization for these cookies: {}", cookieNames);

        // Return empty map - let the calling service handle missing categorization gracefully
        return Collections.emptyMap();
    }

    public CookieCategorizationResponse categorizeSingleCookie(String cookieName) throws CookieCategorizationException, UrlValidationException {
        if (cookieName == null || cookieName.trim().isEmpty()) {
            throw new UrlValidationException(ErrorCodes.EMPTY_ERROR,
                    "Cookie name is required for categorization",
                    "categorizeSingleCookie called with null or empty cookieName parameter"
            );
        }

        Map<String, CookieCategorizationResponse> result = categorizeCookies(List.of(cookieName.trim()));
        return result.get(cookieName.trim());
    }

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

        if (!cachedResults.isEmpty()) {
            log.debug("Retrieved {} cached results out of {} requested cookies", cachedResults.size(), cookieNames.size());
        }
        return cachedResults;
    }

    private Map<String, CookieCategorizationResponse> parseApiResponse(ResponseEntity<String> response, List<String> requestedCookies) throws CookieCategorizationException {
        try {
            if (!response.getStatusCode().is2xxSuccessful()) {
                throw new CookieCategorizationException("External categorization API returned error status: " + response.getStatusCode());
            }

            String responseBody = response.getBody();
            log.debug("Raw API response (length: {}): {}",
                    responseBody != null ? responseBody.length() : 0,
                    responseBody != null && responseBody.length() > 200 ? responseBody.substring(0, 200) + "..." : responseBody);

            if (responseBody == null || responseBody.trim().isEmpty()) {
                throw new CookieCategorizationException("External categorization API returned empty response");
            }

            List<CookieCategorizationResponse> responses = objectMapper.readValue(
                    responseBody,
                    new TypeReference<List<CookieCategorizationResponse>>() {}
            );

            log.debug("Successfully parsed {} categorization responses from API", responses.size());

            Map<String, CookieCategorizationResponse> resultMap = responses.stream()
                    .filter(resp -> resp != null && resp.getName() != null)
                    .collect(Collectors.toMap(
                            CookieCategorizationResponse::getName,
                            resp -> resp,
                            (existing, replacement) -> existing
                    ));

            log.debug("Mapped {} valid responses to cookie names", resultMap.size());
            return resultMap;

        } catch (Exception e) {
            log.error("Error parsing API response: {}", e.getMessage(), e);
            throw new CookieCategorizationException("Failed to parse categorization API response: " + e.getMessage(), e);
        }
    }

    private void cacheResults(Map<String, CookieCategorizationResponse> results) {
        if (!cacheEnabled || results.isEmpty()) return;

        Instant expiryTime = Instant.now().plus(Duration.ofMinutes(cacheTtlMinutes));

        for (Map.Entry<String, CookieCategorizationResponse> entry : results.entrySet()) {
            CacheEntry cacheEntry = new CacheEntry(entry.getValue(), expiryTime);
            categorizationCache.put(entry.getKey(), cacheEntry);
        }

        log.debug("Cached {} categorization results with TTL: {} minutes", results.size(), cacheTtlMinutes);
    }

    private void cleanExpiredEntries() {
        if (!cacheEnabled) return;

        Instant now = Instant.now();
        int sizeBefore = categorizationCache.size();
        categorizationCache.entrySet().removeIf(entry -> !entry.getValue().isValid(now));
        int sizeAfter = categorizationCache.size();

        if (sizeBefore > sizeAfter) {
            log.debug("Cleaned {} expired cache entries ({} -> {})", sizeBefore - sizeAfter, sizeBefore, sizeAfter);
        }
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