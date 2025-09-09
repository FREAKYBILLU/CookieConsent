package com.example.scanner.controller;

import com.example.scanner.dto.CookieUpdateRequest;
import com.example.scanner.dto.CookieUpdateResponse;
import com.example.scanner.dto.ScanStatusResponse;
import com.example.scanner.entity.ScanResultEntity;
import com.example.scanner.exception.ScanExecutionException;
import com.example.scanner.exception.TransactionNotFoundException;
import com.example.scanner.exception.UrlValidationException;
import com.example.scanner.repository.ScanResultRepository;
import com.example.scanner.service.CookieService;
import com.example.scanner.service.ScanService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.ILoggerFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import jakarta.validation.Valid;

import java.util.Map;
import java.util.Optional;

@RestController
@CrossOrigin(origins = "*", allowedHeaders = "*")
@Tag(name = "Cookie Scanner", description = "DPDPA Compliant Cookie Scanning and Management APIs")
public class ScanController {

  private static final Logger log = LoggerFactory.getLogger(ScanController.class);

  private final ScanService scanService;
  private final ScanResultRepository repository;
  private final CookieService cookieService;

  public ScanController(ScanService scanService,
                        ScanResultRepository repository,
                        CookieService cookieService) {
    this.scanService = scanService;
    this.repository = repository;
    this.cookieService = cookieService;
  }

  @Operation(
          summary = "Start Website Cookie Scan",
          description = "Initiates a comprehensive cookie scan of the specified website URL. Returns a transaction ID to track the scan progress."
  )
  @ApiResponse(responseCode = "200", description = "Scan initiated successfully")
  @ApiResponse(responseCode = "400", description = "Invalid URL provided")
  @ApiResponse(responseCode = "500", description = "Failed to initiate scan")
  @PostMapping("/scan")
  public ResponseEntity<Map<String, String>> scanUrl(
          @Parameter(description = "Request body containing the URL to scan", required = true)
          @RequestBody Map<String, String> requestMap) throws UrlValidationException, ScanExecutionException {

    String url = requestMap.get("url");

    if (url == null || url.trim().isEmpty()) {
      throw new UrlValidationException("URL is required and cannot be empty");
    }

    log.info("Received scan request for URL: {}", url);

    try {
      String transactionId = scanService.startScan(url);
      log.info("Scan initiated successfully with transactionId: {}", transactionId);
      return ResponseEntity.ok(Map.of("transactionId", transactionId));
    } catch (UrlValidationException e) {
      log.warn("URL validation failed for: {}", url);
      throw e; // Let global handler manage it
    } catch (Exception e) {
      log.error("Unexpected error starting scan for URL: {}", url, e);
      throw new ScanExecutionException("Failed to initiate scan", e);
    }
  }

  @Operation(
          summary = "Get Scan Status",
          description = "Retrieves the current status and results of a cookie scan using the transaction ID."
  )
  @ApiResponse(responseCode = "200", description = "Scan status retrieved successfully")
  @ApiResponse(responseCode = "400", description = "Invalid transaction ID")
  @ApiResponse(responseCode = "404", description = "Transaction ID not found")
  @GetMapping("/status/{transactionId}")
  public ResponseEntity<ScanStatusResponse> getStatus(
          @Parameter(description = "Transaction ID from the scan request", required = true)
          @PathVariable("transactionId") String transactionId) throws TransactionNotFoundException, ScanExecutionException {

    if (transactionId == null || transactionId.trim().isEmpty()) {
      throw new IllegalArgumentException("Transaction ID is required and cannot be empty");
    }

    log.debug("Retrieving status for transactionId: {}", transactionId);

    try {
      // Force fresh query by using findByTransactionId instead of findById
      Optional<ScanResultEntity> resultOpt = repository.findByTransactionId(transactionId);

      if (resultOpt.isEmpty()) {
        log.warn("Transaction not found: {}", transactionId);
        throw new TransactionNotFoundException(transactionId);
      }

      ScanResultEntity result = resultOpt.get();

      // Log current cookie count for debugging
      int cookieCount = result.getCookies() != null ? result.getCookies().size() : 0;
      log.debug("Retrieved transaction {} with status {} and {} cookies",
              transactionId, result.getStatus(), cookieCount);

      ScanStatusResponse response = new ScanStatusResponse(
              result.getTransactionId(),
              result.getStatus(),
              result.getCookies()
      );

      return ResponseEntity.ok(response);

    } catch (TransactionNotFoundException e) {
      throw e; // Let global handler manage it
    } catch (Exception e) {
      log.error("Unexpected error retrieving status for transactionId: {}", transactionId, e);
      throw new ScanExecutionException("Failed to retrieve scan status", e);
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
          @Valid @RequestBody CookieUpdateRequest updateRequest) throws ScanExecutionException {

    if (transactionId == null || transactionId.trim().isEmpty()) {
      throw new IllegalArgumentException("Transaction ID is required and cannot be empty");
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

    } catch (Exception e) {
      log.error("Error processing cookie update request for transactionId: {}", transactionId, e);
      throw new ScanExecutionException("Failed to update cookie", e);
    }
  }

  @Operation(
          summary = "Health Check",
          description = "Basic health check endpoint to verify service availability."
  )
  @ApiResponse(responseCode = "200", description = "Service is healthy")
  @GetMapping("/health")
  public ResponseEntity<Map<String, String>> healthCheck() {
    log.debug("Health check requested");
    return ResponseEntity.ok(Map.of(
            "status", "UP",
            "service", "Cookie Scanner API",
            "timestamp", java.time.Instant.now().toString()
    ));
  }
}