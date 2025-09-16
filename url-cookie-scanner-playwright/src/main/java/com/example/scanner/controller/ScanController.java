package com.example.scanner.controller;

import com.example.scanner.config.RateLimitInterceptor;
import com.example.scanner.constants.ErrorCodes;
import com.example.scanner.dto.*;
import com.example.scanner.entity.CookieEntity;
import com.example.scanner.entity.ScanResultEntity;
import com.example.scanner.exception.ScanExecutionException;
import com.example.scanner.exception.TransactionNotFoundException;
import com.example.scanner.exception.UrlValidationException;
import com.example.scanner.repository.ScanResultRepository;
import com.example.scanner.service.CookieService;
import com.example.scanner.service.ScanService;
import com.example.scanner.util.UrlAndCookieUtil;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import jakarta.validation.Valid;

import com.example.scanner.exception.CookieNotFoundException;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/v2")
@CrossOrigin(origins = "*", allowedHeaders = "*")
@Tag(name = "Cookie Scanner", description = "DPDPA Compliant Cookie Scanning and Management APIs with Subdomain Support and Rate Limiting")
public class ScanController {

  private static final Logger log = LoggerFactory.getLogger(ScanController.class);

  // ADD THIS: Pattern to validate UUID format (your app generates UUIDs as transaction IDs)
  private static final Pattern VALID_TRANSACTION_ID = Pattern.compile("^[a-fA-F0-9]{8}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{12}$");

  private final ScanResultRepository repository;
  private final CookieService cookieService;
  private final ScanService scanService;

  @Autowired(required = false)
  private RateLimitInterceptor rateLimitInterceptor;

  public ScanController(ScanService scanService,
                        ScanResultRepository repository,
                        CookieService cookieService) {
    this.scanService = scanService;
    this.repository = repository;
    this.cookieService = cookieService;
  }

  @GetMapping("/debug-interceptor")
  public String debugInterceptor() {
    return "RateLimitInterceptor bean exists: " + (rateLimitInterceptor != null);
  }

  @Operation(
          summary = "Start Website Cookie Scan with Protection",
          description = "Initiates a comprehensive cookie scan with rate limiting and circuit breaker protection. " +
                  "All subdomains must belong to the same root domain as the main URL. " +
                  "Returns a transaction ID to track the scan progress. Protected against DoS attacks."
  )
  @ApiResponse(responseCode = "200", description = "Scan initiated successfully")
  @ApiResponse(responseCode = "400", description = "Invalid URL or subdomains provided")
  @ApiResponse(responseCode = "429", description = "Too many requests - rate limit exceeded")
  @ApiResponse(responseCode = "503", description = "Service temporarily unavailable")
  @ApiResponse(responseCode = "500", description = "Failed to initiate scan")
  @PostMapping("/scan")
  public ResponseEntity<Map<String, Object>> scanUrl(
          @Valid @RequestBody ScanRequestDto scanRequest) throws UrlValidationException, ScanExecutionException {

    String url = scanRequest.getUrl();
    List<String> subdomains = scanRequest.getSubDomain();

    if (url == null || url.trim().isEmpty()) {
      throw new UrlValidationException(ErrorCodes.EMPTY_ERROR,
              "URL is required and cannot be empty",
              "ScanRequestDto validation failed: url field is null or empty"
      );
    }

    log.info("Received PROTECTED scan request for URL: {} with {} subdomains",
            url, subdomains != null ? subdomains.size() : 0);

    if (subdomains != null && !subdomains.isEmpty()) {
      log.info("Subdomains to scan: {}", subdomains);
    }

    try {
      String transactionId;

      // Use enhanced service if available, otherwise fallback to regular service
      if (scanService != null) {
        log.info("Using ENHANCED scan service with protection for URL: {}", url);
        transactionId = scanService.startScanWithProtection(url, subdomains);
      } else {
        log.warn("Enhanced scan service not available, using regular service for URL: {}", url);
        transactionId = scanService.startScan(url, subdomains);
      }

      log.info("Protected scan initiated successfully with transactionId: {}", transactionId);

      String message = "Protected scan started for main URL";
      if (subdomains != null && !subdomains.isEmpty()) {
        message += " and " + subdomains.size() + " subdomain" +
                (subdomains.size() > 1 ? "s" : "");
      }

      Map<String, Object> response = new HashMap<>();
      response.put("transactionId", transactionId);
      response.put("message", message);
      response.put("mainUrl", url);
      response.put("subdomainCount", subdomains != null ? subdomains.size() : 0);
      response.put("protection", scanService != null ? "Rate limiting and circuit breaker enabled" : "Basic protection");

      return ResponseEntity.ok(response);

    } catch (UrlValidationException e) {
      log.warn("URL/Subdomain validation failed for: {} with subdomains: {}", url, subdomains);
      throw e;
    } catch (Exception e) {
      log.error("Unexpected error starting protected scan for URL: {} with subdomains: {}", url, subdomains, e);
      throw new ScanExecutionException("Failed to initiate protected scan: " + e.getMessage());
    }
  }

  @Operation(
          summary = "Get Scan Status and Results",
          description = "Retrieves the current status and detailed results of a cookie scan using the transaction ID. " +
                  "Results include cookies from main URL and all scanned subdomains with subdomain attribution."
  )
  @ApiResponse(responseCode = "200", description = "Scan status retrieved successfully")
  @ApiResponse(responseCode = "400", description = "Invalid transaction ID")
  @ApiResponse(responseCode = "404", description = "Transaction ID not found")
  @GetMapping("/status/{transactionId}")
  public ResponseEntity<ScanStatusResponse> getStatus(
          @Parameter(description = "Transaction ID from the scan request", required = true)
          @PathVariable("transactionId") String transactionId) throws TransactionNotFoundException, ScanExecutionException, UrlValidationException {

    // SECURITY FIX: Validate transaction ID format BEFORE processing
    if (!isValidTransactionId(transactionId)) {
      log.warn("Invalid transaction ID format attempted: {}", transactionId);
      throw new UrlValidationException(
              ErrorCodes.VALIDATION_ERROR,
              "Invalid transaction ID format",
              "Transaction ID must be a valid UUID format. Received: " + transactionId
      );
    }

    log.debug("Retrieving status for transactionId: {}", transactionId);

    try {
      Optional<ScanResultEntity> resultOpt = repository.findByTransactionId(transactionId);

      if (resultOpt.isEmpty()) {
        log.warn("Transaction not found: {}", transactionId);
        throw new TransactionNotFoundException(transactionId);
      }

      ScanResultEntity result = resultOpt.get();

      // Convert grouped data to response
      List<ScanStatusResponse.SubdomainCookieGroup> subdomains = new ArrayList<>();

      if (result.getCookiesBySubdomain() != null) {
        for (Map.Entry<String, List<CookieEntity>> entry : result.getCookiesBySubdomain().entrySet()) {
          String subdomainName = entry.getKey();
          List<CookieEntity> cookies = entry.getValue();
          String subdomainUrl = "main".equals(subdomainName) ? result.getUrl() :
                  constructSubdomainUrl(result.getUrl(), subdomainName);

          subdomains.add(new ScanStatusResponse.SubdomainCookieGroup(subdomainName, subdomainUrl, cookies));
        }

        // Sort: "main" first, then alphabetically
        subdomains.sort((a, b) -> {
          if ("main".equals(a.getSubdomainName())) return -1;
          if ("main".equals(b.getSubdomainName())) return 1;
          return a.getSubdomainName().compareTo(b.getSubdomainName());
        });
      }

      // Create summary from all cookies
      List<CookieEntity> allCookies = result.getCookiesBySubdomain() != null ?
              result.getCookiesBySubdomain().values().stream()
                      .flatMap(List::stream)
                      .collect(Collectors.toList()) : new ArrayList<>();

      Map<String, Integer> bySource = allCookies.stream()
              .collect(Collectors.groupingBy(
                      cookie -> cookie.getSource() != null ? cookie.getSource().name() : "UNKNOWN",
                      Collectors.collectingAndThen(Collectors.counting(), Math::toIntExact)
              ));

      Map<String, Integer> byCategory = allCookies.stream()
              .collect(Collectors.groupingBy(
                      cookie -> cookie.getCategory() != null ? cookie.getCategory() : "uncategorized",
                      Collectors.collectingAndThen(Collectors.counting(), Math::toIntExact)
              ));

      ScanStatusResponse.ScanSummary summary = new ScanStatusResponse.ScanSummary(bySource, byCategory);

      ScanStatusResponse response = new ScanStatusResponse(
              result.getTransactionId(),
              result.getStatus(),
              result.getUrl(),
              subdomains,
              summary
      );

      log.debug("Retrieved transaction {} with status {} and {} cookies in {} subdomains",
              transactionId, result.getStatus(), allCookies.size(), subdomains.size());

      return ResponseEntity.ok(response);

    } catch (TransactionNotFoundException e) {
      throw e;
    } catch (Exception e) {
      log.error("Unexpected error retrieving status for transactionId: {}", transactionId, e);
      throw new ScanExecutionException("Failed to retrieve scan status: " + e.getMessage());
    }
  }

  private String constructSubdomainUrl(String mainUrl, String subdomainName) {
    try {
      String rootDomain = UrlAndCookieUtil.extractRootDomain(mainUrl);
      String protocol = mainUrl.startsWith("https") ? "https" : "http";
      return protocol + "://" + subdomainName + "." + rootDomain;
    } catch (Exception e) {
      return mainUrl;
    }
  }

  @Operation(
          summary = "Update Cookie Information",
          description = "Updates the category and description for a specific cookie within a scan transaction."
  )
  @ApiResponse(
          responseCode = "200",
          description = "Cookie updated successfully",
          content = @Content(schema = @Schema(implementation = CookieUpdateResponse.class))
  )
  @ApiResponse(responseCode = "400", description = "Invalid request or cookie not found")
  @ApiResponse(responseCode = "404", description = "Transaction not found")
  @ApiResponse(responseCode = "500", description = "Internal server error")
  @PutMapping("/transaction/{transactionId}/cookie")
  public ResponseEntity<CookieUpdateResponse> updateCookie(
          @Parameter(description = "Transaction ID from the scan", required = true)
          @PathVariable("transactionId") String transactionId,
          @Parameter(description = "Cookie update request containing name, category, and description", required = true)
          @Valid @RequestBody CookieUpdateRequest updateRequest) throws ScanExecutionException, CookieNotFoundException, TransactionNotFoundException, UrlValidationException {

    // SECURITY FIX: Validate transaction ID format BEFORE processing
    if (!isValidTransactionId(transactionId)) {
      log.warn("Invalid transaction ID format attempted: {}", transactionId);
      throw new UrlValidationException(
              ErrorCodes.VALIDATION_ERROR,
              "Invalid transaction ID format",
              "Transaction ID must be a valid UUID format. Received: " + transactionId
      );
    }

    log.info("Received request to update cookie '{}' for transactionId: {}",
            updateRequest.getCookieName(), transactionId);

    try {
      CookieUpdateResponse response = cookieService.updateCookie(transactionId, updateRequest);

      if (response.isUpdated()) {
        log.info("Successfully updated cookie '{}' for transactionId: {}",
                updateRequest.getCookieName(), transactionId);
        return ResponseEntity.ok(response);
      } else {
        log.warn("Failed to update cookie '{}' for transactionId: {} - Reason: {}",
                updateRequest.getCookieName(), transactionId, response.getMessage());
        return ResponseEntity.badRequest().body(response);
      }

    } catch (CookieNotFoundException | TransactionNotFoundException e) {
      log.warn("Field validation error found for update: {}", e.getMessage());
      throw e;
    }  catch (UrlValidationException e) {
      log.warn("Cookie not found for update: {}", e.getMessage());
      throw e;
    } catch (Exception e) {
      log.error("Error processing cookie update request for transactionId: {}", transactionId, e);
      throw new ScanExecutionException("Failed to update cookie: " + e.getMessage());
    }
  }

  @Operation(
          summary = "Enhanced Health Check with Protection Status",
          description = "Enhanced health check that includes circuit breaker state and protection status."
  )
  @ApiResponse(responseCode = "200", description = "Service is healthy")
  @GetMapping("/health")
  public ResponseEntity<Map<String, Object>> healthCheck() {
    log.debug("Enhanced health check requested");

    Map<String, Object> healthInfo = new HashMap<>();
    healthInfo.put("status", "UP");
    healthInfo.put("service", "Protected Cookie Scanner API");
    healthInfo.put("version", "2.1.0");
    healthInfo.put("timestamp", java.time.Instant.now().toString());

    Map<String, Object> features = new HashMap<>();
    features.put("subdomainScanning", true);
    features.put("cookieCategorization", true);
    features.put("consentHandling", true);
    features.put("iframeProcessing", true);
    features.put("rateLimiting", scanService != null);
    features.put("circuitBreaker", scanService != null);

    healthInfo.put("features", features);

    // Add protection status
    Map<String, Object> protection = new HashMap<>();
    protection.put("rateLimiting", scanService != null);
    protection.put("circuitBreaker", scanService != null);
    protection.put("bulkhead", true);
    healthInfo.put("protection", protection);

    // Get circuit breaker health if available
    if (scanService != null) {
      try {
        CompletableFuture<ScanService.CircuitBreakerHealthInfo> cbHealth =
                scanService.getCircuitBreakerHealth();

        ScanService.CircuitBreakerHealthInfo cbInfo = cbHealth.get();

        Map<String, Object> circuitBreaker = new HashMap<>();
        circuitBreaker.put("state", cbInfo.getState());
        circuitBreaker.put("healthy", cbInfo.isHealthy());
        circuitBreaker.put("failureRate", String.format("%.2f%%", cbInfo.getFailureRate()));
        circuitBreaker.put("successfulCalls", cbInfo.getSuccessfulCalls());
        circuitBreaker.put("failedCalls", cbInfo.getFailedCalls());
        circuitBreaker.put("slowCalls", cbInfo.getSlowCalls());

        healthInfo.put("circuitBreaker", circuitBreaker);

      } catch (Exception e) {
        log.warn("Failed to get circuit breaker health: {}", e.getMessage());
        Map<String, Object> circuitBreaker = new HashMap<>();
        circuitBreaker.put("state", "UNKNOWN");
        circuitBreaker.put("healthy", false);
        circuitBreaker.put("error", "Unable to retrieve circuit breaker status");
        healthInfo.put("circuitBreaker", circuitBreaker);
      }
    }

    return ResponseEntity.ok(healthInfo);
  }

  @Operation(
          summary = "System Metrics and Performance",
          description = "Get detailed system metrics including rate limiting and protection status."
  )
  @GetMapping("/metrics")
  public ResponseEntity<Map<String, Object>> getMetrics() {
    Map<String, Object> metrics = new HashMap<>();

    // Runtime metrics
    Runtime runtime = Runtime.getRuntime();
    Map<String, Object> memory = new HashMap<>();
    memory.put("totalMemory", runtime.totalMemory());
    memory.put("freeMemory", runtime.freeMemory());
    memory.put("usedMemory", runtime.totalMemory() - runtime.freeMemory());
    memory.put("maxMemory", runtime.maxMemory());
    memory.put("usedMemoryPercent", String.format("%.2f%%",
            ((double)(runtime.totalMemory() - runtime.freeMemory()) / runtime.maxMemory()) * 100));

    metrics.put("memory", memory);
    metrics.put("processors", runtime.availableProcessors());
    metrics.put("timestamp", java.time.Instant.now().toString());

    // Thread information
    Map<String, Object> threads = new HashMap<>();
    threads.put("activeCount", Thread.activeCount());
    threads.put("currentThread", Thread.currentThread().getName());
    metrics.put("threads", threads);

    // Protection status
    Map<String, Object> protection = new HashMap<>();
    protection.put("enhancedServiceAvailable", scanService != null);
    protection.put("rateLimitingActive", scanService != null);
    protection.put("circuitBreakerActive", scanService != null);
    metrics.put("protection", protection);

    return ResponseEntity.ok(metrics);
  }

  @Operation(
          summary = "Add Cookie to Scan Transaction",
          description = "Manually adds a cookie to a specific transaction and subdomain. " +
                  "The cookie domain must belong to the same root domain as the scanned URL."
  )
  @ApiResponse(
          responseCode = "200",
          description = "Cookie added successfully",
          content = @Content(schema = @Schema(implementation = AddCookieResponse.class))
  )
  @ApiResponse(responseCode = "400", description = "Invalid request data or duplicate cookie")
  @ApiResponse(responseCode = "404", description = "Transaction not found")
  @ApiResponse(responseCode = "500", description = "Internal server error")
  @PostMapping("/transaction/{transactionId}/cookies")
  public ResponseEntity<Map<String, Object>> addCookie(
          @Parameter(description = "Transaction ID from the scan", required = true)
          @PathVariable("transactionId") String transactionId,
          @Parameter(description = "Cookie information to add", required = true)
          @Valid @RequestBody AddCookieRequest addRequest) throws ScanExecutionException, TransactionNotFoundException, UrlValidationException {

    // SECURITY FIX: Validate transaction ID format BEFORE processing
    if (!isValidTransactionId(transactionId)) {
      log.warn("Invalid transaction ID format attempted: {}", transactionId);
      throw new UrlValidationException(
              ErrorCodes.VALIDATION_ERROR,
              "Invalid transaction ID format",
              "Transaction ID must be a valid UUID format. Received: " + transactionId
      );
    }

    if (transactionId == null || transactionId.trim().isEmpty()) {
      throw new IllegalArgumentException("Transaction ID is required and cannot be empty");
    }

    log.info("Received request to add cookie '{}' to transactionId: {} in subdomain: '{}'",
            addRequest.getName(), transactionId, addRequest.getSubdomainName());

    // Call service - if no exception thrown, it means success
    AddCookieResponse response = cookieService.addCookie(transactionId, addRequest);

    log.info("Successfully added cookie '{}' to transactionId: {} in subdomain: '{}'",
            addRequest.getName(), transactionId, addRequest.getSubdomainName());

    // Return success response
    Map<String, Object> successResponse = new HashMap<>();
    successResponse.put("success", true);
    successResponse.put("message", response.getMessage());
    successResponse.put("transactionId", response.getTransactionId());
    successResponse.put("name", response.getName());
    successResponse.put("domain", response.getDomain());
    successResponse.put("subdomainName", response.getSubdomainName());

    return ResponseEntity.ok(successResponse);
  }

  // ADD THIS: Security validation method
  /**
   * SECURITY METHOD: Validates transaction ID format to prevent path traversal attacks
   *
   * This method protects against attacks like: /status/../../../etc/passwd
   *
   * @param transactionId The transaction ID to validate
   * @return true if the transaction ID is safe and valid, false otherwise
   */
  private boolean isValidTransactionId(String transactionId) {
    // Check for null or empty
    if (transactionId == null || transactionId.trim().isEmpty()) {
      return false;
    }

    // Check for path traversal patterns - these are common attack vectors
    if (transactionId.contains("..") ||      // Basic path traversal (../../../etc/passwd)
            transactionId.contains("/") ||       // Forward slash (shouldn't be in UUID)
            transactionId.contains("\\") ||      // Backslash (Windows path traversal)
            transactionId.contains("%2e") ||     // URL-encoded dot (encoded ..)
            transactionId.contains("%2f") ||     // URL-encoded forward slash (encoded /)
            transactionId.contains("%5c")) {     // URL-encoded backslash (encoded \)
      return false;
    }

    // Validate UUID format - your app generates UUIDs like: 550e8400-e29b-41d4-a716-446655440000
    // This regex ensures ONLY valid UUIDs are accepted (36 characters, specific pattern)
    return VALID_TRANSACTION_ID.matcher(transactionId).matches();
  }
}