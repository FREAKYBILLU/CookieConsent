package com.example.scanner.controller;

import com.example.scanner.constants.ErrorCodes;
import com.example.scanner.dto.request.AddCookieRequest;
import com.example.scanner.dto.request.CookieUpdateRequest;
import com.example.scanner.dto.request.ScanRequestDto;
import com.example.scanner.dto.response.AddCookieResponse;
import com.example.scanner.dto.response.CookieUpdateResponse;
import com.example.scanner.dto.response.ErrorResponse;
import com.example.scanner.dto.response.ScanStatusResponse;
import com.example.scanner.entity.CookieEntity;
import com.example.scanner.entity.ScanResultEntity;
import com.example.scanner.exception.ScanExecutionException;
import com.example.scanner.exception.TransactionNotFoundException;
import com.example.scanner.exception.UrlValidationException;
import com.example.scanner.service.CookieService;
import com.example.scanner.service.ScanService;
import com.example.scanner.util.CommonUtil;
import com.example.scanner.util.UrlAndCookieUtil;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import jakarta.validation.Valid;

import com.example.scanner.exception.CookieNotFoundException;

import java.util.*;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/")
@RequiredArgsConstructor
@CrossOrigin(origins = "*", allowedHeaders = "*")
@Tag(name = "Cookie Scanner", description = "DPDPA Compliant Cookie Scanning and Management APIs with Subdomain Support and Rate Limiting")
public class ScanController {

  private static final Logger log = LoggerFactory.getLogger(ScanController.class);
  private final CookieService cookieService;
  private final ScanService scanService;

    @Operation(
            summary = "Start Website Cookie Scan with Protection",
            description = "Initiates a comprehensive cookie scan with rate limiting and circuit breaker protection. " +
                    "All subdomains must belong to the same root domain as the main URL. " +
                    "Returns a transaction ID to track the scan progress. Protected against DoS attacks.",
            responses = {
                    @ApiResponse(
                            responseCode = "200",
                            description = "Scan initiated successfully",
                            content = @Content(schema = @Schema(implementation = Map.class))
                    ),
                    @ApiResponse(
                            responseCode = "400",
                            description = "Invalid URL or subdomains provided",
                            content = @Content(schema = @Schema(implementation = ErrorResponse.class))
                    ),
                    @ApiResponse(
                            responseCode = "429",
                            description = "Too many requests - rate limit exceeded",
                            content = @Content(schema = @Schema(implementation = ErrorResponse.class))
                    ),
                    @ApiResponse(
                            responseCode = "503",
                            description = "Service temporarily unavailable",
                            content = @Content(schema = @Schema(implementation = ErrorResponse.class))
                    ),
                    @ApiResponse(
                            responseCode = "500",
                            description = "Failed to initiate scan",
                            content = @Content(schema = @Schema(implementation = ErrorResponse.class))
                    )
            }
    )
  @PostMapping("/scan")
  public ResponseEntity<Map<String, Object>> scanUrl(
          @RequestHeader("X-Tenant-ID") String tenantId,
          @Valid @RequestBody ScanRequestDto scanRequest) throws UrlValidationException, ScanExecutionException {

    String url = scanRequest.getUrl();
    List<String> subdomains = scanRequest.getSubDomain();

    if (url == null || url.trim().isEmpty()) {
      throw new UrlValidationException(ErrorCodes.EMPTY_ERROR,
              "URL is required and cannot be empty",
              "ScanRequestDto validation failed: url field is null or empty"
      );
    }

    log.info("Received scan request for URL: {} with {} subdomains",
            url, subdomains != null ? subdomains.size() : 0);

    if (subdomains != null && !subdomains.isEmpty()) {
      log.info("Subdomains to scan: {}", subdomains);
    }

    try {
      String transactionId;
        log.info("Using ENHANCED scan service with protection for URL: {}", url);
        transactionId = scanService.startScan(tenantId, url, subdomains);

      log.info("Scan initiated successfully with transactionId: {}", transactionId);

      String message = "Scan started for main URL";
      if (subdomains != null && !subdomains.isEmpty()) {
        message += " and " + subdomains.size() + " subdomain" +
                (subdomains.size() > 1 ? "s" : "");
      }

      Map<String, Object> response = new HashMap<>();
      response.put("transactionId", transactionId);
      response.put("message", message);
      response.put("mainUrl", url);
      response.put("subdomainCount", subdomains != null ? subdomains.size() : 0);

      return ResponseEntity.ok(response);

    } catch (UrlValidationException e) {
      log.warn("URL/Subdomain validation failed for: {} with subdomains: {}", url, subdomains);
      throw e;
    } catch (Exception e) {
      log.error("Unexpected error starting scan for URL: {} with subdomains: {}", url, subdomains, e);
      throw new ScanExecutionException("Failed to initiate scan: " + e.getMessage());
    }
  }

    @Operation(
            summary = "Get Scan Status and Results",
            description = "Retrieves the current status and detailed results of a cookie scan using the transaction ID. " +
                    "Results include cookies from main URL and all scanned subdomains with subdomain attribution.",
            responses = {
                    @ApiResponse(
                            responseCode = "200",
                            description = "Scan status retrieved successfully",
                            content = @Content(schema = @Schema(implementation = ScanStatusResponse.class))
                    ),
                    @ApiResponse(
                            responseCode = "400",
                            description = "Invalid transaction ID",
                            content = @Content(schema = @Schema(implementation = ErrorResponse.class))
                    ),
                    @ApiResponse(
                            responseCode = "404",
                            description = "Transaction ID not found",
                            content = @Content(schema = @Schema(implementation = ErrorResponse.class))
                    ),
                    @ApiResponse(
                            responseCode = "500",
                            description = "Internal server error",
                            content = @Content(schema = @Schema(implementation = ErrorResponse.class))
                    )
            }
    )
  @GetMapping("/status/{transactionId}")
  public ResponseEntity<ScanStatusResponse> getStatus(
          @RequestHeader("X-Tenant-ID") String tenantId,
          @Parameter(description = "Transaction ID from the scan request", required = true)
          @PathVariable("transactionId") String transactionId) throws TransactionNotFoundException, ScanExecutionException, UrlValidationException {

    if (!CommonUtil.isValidTransactionId(transactionId)) {
      log.warn("Invalid transaction ID format attempted: {}", transactionId);
      throw new UrlValidationException(
              ErrorCodes.VALIDATION_ERROR,
              "Invalid transaction ID format",
              "Transaction ID must be a valid UUID format. Received: " + transactionId
      );
    }

    log.debug("Retrieving status for transactionId: {}", transactionId);

    try {
      Optional<ScanResultEntity> resultOpt = scanService.getScanResult(tenantId, transactionId);
      if (resultOpt.isEmpty()) {
        log.warn("Transaction not found: {}", transactionId);
        throw new TransactionNotFoundException(transactionId);
      }

      ScanResultEntity result = resultOpt.get();

      List<ScanStatusResponse.SubdomainCookieGroup> subdomains = new ArrayList<>();

      if (result.getCookiesBySubdomain() != null) {
        for (Map.Entry<String, List<CookieEntity>> entry : result.getCookiesBySubdomain().entrySet()) {
          String subdomainName = entry.getKey();
          List<CookieEntity> cookies = entry.getValue();
          String subdomainUrl = "main".equals(subdomainName) ? result.getUrl() :
                  constructSubdomainUrl(result.getUrl(), subdomainName);

          subdomains.add(new ScanStatusResponse.SubdomainCookieGroup(subdomainName, subdomainUrl, cookies));
        }

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
                      .toList() : new ArrayList<>();

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
            description = "Updates the category, description, domain, privacy policy URL, and expires date for a specific cookie within a scan transaction.",
            responses = {
                    @ApiResponse(
                            responseCode = "200",
                            description = "Cookie updated successfully",
                            content = @Content(schema = @Schema(implementation = CookieUpdateResponse.class))
                    ),
                    @ApiResponse(
                            responseCode = "400",
                            description = "Invalid request or cookie not found",
                            content = @Content(schema = @Schema(implementation = ErrorResponse.class))
                    ),
                    @ApiResponse(
                            responseCode = "404",
                            description = "Transaction not found",
                            content = @Content(schema = @Schema(implementation = ErrorResponse.class))
                    ),
                    @ApiResponse(
                            responseCode = "500",
                            description = "Internal server error",
                            content = @Content(schema = @Schema(implementation = ErrorResponse.class))
                    )
            }
    )
  @PutMapping("/transaction/{transactionId}/cookie")
  public ResponseEntity<CookieUpdateResponse> updateCookie(
          @RequestHeader("X-Tenant-ID") String tenantId,
          @Parameter(description = "Transaction ID from the scan", required = true)
          @PathVariable("transactionId") String transactionId,
          @Parameter(description = "Cookie update request containing name, category, description, domain, privacy policy URL, and expires", required = true)
          @Valid @RequestBody CookieUpdateRequest updateRequest) throws ScanExecutionException, CookieNotFoundException, TransactionNotFoundException, UrlValidationException {

    if (!CommonUtil.isValidTransactionId(transactionId)) {
      log.warn("Invalid transaction ID format attempted: {}", transactionId);
      throw new UrlValidationException(
              ErrorCodes.VALIDATION_ERROR,
              "Invalid transaction ID format",
              "Transaction ID must be a valid UUID format. Received: " + transactionId
      );
    }

    log.info("Received request to update cookie '{}' for transactionId: {}",
            updateRequest.getName(), transactionId); // CHANGED: cookieName to name

    try {
      CookieUpdateResponse response = cookieService.updateCookie(tenantId, transactionId, updateRequest);

      if (response.isUpdated()) {
        log.info("Successfully updated cookie '{}' for transactionId: {}",
                updateRequest.getName(), transactionId); // CHANGED: cookieName to name
        return ResponseEntity.ok(response);
      } else {
        log.warn("Failed to update cookie '{}' for transactionId: {} - Reason: {}",
                updateRequest.getName(), transactionId, response.getMessage()); // CHANGED: cookieName to name
        return ResponseEntity.badRequest().body(response);
      }

    } catch (CookieNotFoundException | TransactionNotFoundException e) {
      log.warn("Field validation error found for update: {}", e.getMessage());
      throw e;
    } catch (UrlValidationException e) {
      log.warn("Cookie not found for update: {}", e.getMessage());
      throw e;
    } catch (Exception e) {
      log.error("Error processing cookie update request for transactionId: {}", transactionId, e);
      throw new ScanExecutionException("Failed to update cookie: " + e.getMessage());
    }
  }

    @Operation(
            summary = "Enhanced Health Check with Protection Status",
            description = "Enhanced health check that includes circuit breaker state and protection status.",
            responses = {
                    @ApiResponse(
                            responseCode = "200",
                            description = "Service is healthy",
                            content = @Content(schema = @Schema(implementation = Map.class))
                    )
            }
    )
  @GetMapping("/health")
  public ResponseEntity<Map<String, Object>> healthCheck() {
    log.debug("Enhanced health check requested");

    Map<String, Object> healthInfo = new HashMap<>();
    healthInfo.put("status", "UP");
    healthInfo.put("service", "Cookie Scanner API");
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

    return ResponseEntity.ok(healthInfo);
  }

    @Operation(
            summary = "System Metrics and Performance",
            description = "Get detailed system metrics including rate limiting and protection status.",
            responses = {
                    @ApiResponse(
                            responseCode = "200",
                            description = "Metrics retrieved successfully",
                            content = @Content(schema = @Schema(implementation = Map.class))
                    )
            }
    )
  @GetMapping("/metrics")
  public ResponseEntity<Map<String, Object>> getMetrics() {
    Map<String, Object> metrics = new HashMap<>();

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

    Map<String, Object> threads = new HashMap<>();
    threads.put("activeCount", Thread.activeCount());
    threads.put("currentThread", Thread.currentThread().getName());
    metrics.put("threads", threads);

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
                    "The cookie domain must belong to the same root domain as the scanned URL.",
            responses = {
                    @ApiResponse(
                            responseCode = "200",
                            description = "Cookie added successfully",
                            content = @Content(schema = @Schema(implementation = AddCookieResponse.class))
                    ),
                    @ApiResponse(
                            responseCode = "400",
                            description = "Invalid request data or duplicate cookie",
                            content = @Content(schema = @Schema(implementation = ErrorResponse.class))
                    ),
                    @ApiResponse(
                            responseCode = "404",
                            description = "Transaction not found",
                            content = @Content(schema = @Schema(implementation = ErrorResponse.class))
                    ),
                    @ApiResponse(
                            responseCode = "500",
                            description = "Internal server error",
                            content = @Content(schema = @Schema(implementation = ErrorResponse.class))
                    )
            }
    )
  @PostMapping("/transaction/{transactionId}/cookies")
  public ResponseEntity<Map<String, Object>> addCookie(
          @RequestHeader("X-Tenant-ID") String tenantId,
          @Parameter(description = "Transaction ID from the scan", required = true)
          @PathVariable("transactionId") String transactionId,
          @Parameter(description = "Cookie information to add", required = true)
          @Valid @RequestBody AddCookieRequest addRequest) throws ScanExecutionException, TransactionNotFoundException, UrlValidationException {

    if (!CommonUtil.isValidTransactionId(transactionId)) {
      log.warn("Invalid transaction ID format attempted: {}", transactionId);
      throw new UrlValidationException(
              ErrorCodes.VALIDATION_ERROR,
              "Invalid transaction ID format",
              "Transaction ID must be a valid UUID format. Received: " + transactionId
      );
    }

    if (transactionId.trim().isEmpty()) {
      throw new IllegalArgumentException("Transaction ID is required and cannot be empty");
    }

    log.info("Received request to add cookie '{}' to transactionId: {} in subdomain: '{}'",
            addRequest.getName(), transactionId, addRequest.getSubdomainName());

    AddCookieResponse response = cookieService.addCookie(tenantId, transactionId, addRequest);

    log.info("Successfully added cookie '{}' to transactionId: {} in subdomain: '{}'",
            addRequest.getName(), transactionId, addRequest.getSubdomainName());

    Map<String, Object> successResponse = new HashMap<>();
    successResponse.put("success", true);
    successResponse.put("message", response.getMessage());
    successResponse.put("transactionId", response.getTransactionId());
    successResponse.put("name", response.getName());
    successResponse.put("domain", response.getDomain());
    successResponse.put("subdomainName", response.getSubdomainName());

    return ResponseEntity.ok(successResponse);
  }

}