package com.example.scanner.service;

import com.example.scanner.dto.CookieUpdateRequest;
import com.example.scanner.dto.CookieUpdateResponse;
import com.example.scanner.entity.CookieEntity;
import com.example.scanner.entity.ScanResultEntity;
import com.example.scanner.exception.CookieNotFoundException;
import com.example.scanner.exception.ScanExecutionException;
import com.example.scanner.exception.TransactionNotFoundException;
import com.example.scanner.exception.UrlValidationException;
import com.example.scanner.repository.ScanResultRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Service
public class CookieService {

    private static final Logger log = LoggerFactory.getLogger(CookieService.class);

    private final ScanResultRepository scanResultRepository;

    public CookieService(ScanResultRepository scanResultRepository) {
        this.scanResultRepository = scanResultRepository;
    }

    /**
     * Update a specific cookie's category and description within a transaction
     */
    @Transactional
    public CookieUpdateResponse updateCookie(String transactionId, CookieUpdateRequest updateRequest)
            throws TransactionNotFoundException, ScanExecutionException, CookieNotFoundException, UrlValidationException {

        if (transactionId == null || transactionId.trim().isEmpty()) {
            throw new IllegalArgumentException("Transaction ID cannot be null or empty");
        }

        if (updateRequest == null || updateRequest.getCookieName() == null || updateRequest.getCookieName().trim().isEmpty()) {
            throw new UrlValidationException("Cookie name is required for update");
        }

        log.info("Updating cookie '{}' for transactionId: {}", updateRequest.getCookieName(), transactionId);

        try {
            // Find the scan result by transaction ID
            Optional<ScanResultEntity> scanResultOpt = findScanResultByTransactionId(transactionId);

            if (scanResultOpt.isEmpty()) {
                throw new TransactionNotFoundException(transactionId);
            }

            ScanResultEntity scanResult = scanResultOpt.get();

            // Validate scan completion status
            validateScanStatus(scanResult, transactionId);

            // Find and update the specific cookie
            CookieEntity updatedCookie = findAndUpdateCookie(scanResult, updateRequest, transactionId);

            // Save the updated scan result
            saveScanResult(scanResult, transactionId);

            log.info("Successfully updated cookie '{}' for transactionId: {}. Category: {}, Description: {}",
                    updateRequest.getCookieName(), transactionId, updatedCookie.getCategory(), updatedCookie.getDescription());

            return CookieUpdateResponse.success(transactionId, updateRequest.getCookieName(),
                    updatedCookie.getCategory(), updatedCookie.getDescription());

        } catch (TransactionNotFoundException e) {
            log.warn("Transaction not found for cookie update: {}", transactionId);
            throw e;
        } catch (CookieNotFoundException e) {
            log.warn("Cookie not found for update: {}", e.getMessage());
            throw e;
        }catch (DataAccessException e) {
            log.error("Database error during cookie update for transactionId: {}", transactionId, e);
            throw new ScanExecutionException("Database error during cookie update", e);
        } catch (Exception e) {
            log.error("Unexpected error updating cookie '{}' for transactionId: {}",
                    updateRequest.getCookieName(), transactionId, e);
            throw new ScanExecutionException("Unexpected error during cookie update: " + e.getMessage(), e);
        }
    }

    /**
     * Get a specific cookie details from a transaction
     */
    public Optional<CookieEntity> getCookie(String transactionId, String cookieName)
            throws TransactionNotFoundException, ScanExecutionException, UrlValidationException {

        if (transactionId == null || transactionId.trim().isEmpty()) {
            throw new UrlValidationException("Transaction ID is required");
        }

        if (cookieName == null || cookieName.trim().isEmpty()) {
            throw new UrlValidationException("Cookie name is required");
        }

        log.debug("Retrieving cookie '{}' for transactionId: {}", cookieName, transactionId);

        try {
            Optional<ScanResultEntity> scanResultOpt = findScanResultByTransactionId(transactionId);

            if (scanResultOpt.isEmpty()) {
                throw new TransactionNotFoundException(transactionId);
            }

            ScanResultEntity scanResult = scanResultOpt.get();
            List<CookieEntity> cookies = scanResult.getCookies();

            if (cookies == null || cookies.isEmpty()) {
                log.debug("No cookies found for transaction: {}", transactionId);
                return Optional.empty();
            }

            Optional<CookieEntity> foundCookie = cookies.stream()
                    .filter(cookie -> cookieName.equals(cookie.getName()))
                    .findFirst();

            if (foundCookie.isPresent()) {
                log.debug("Cookie '{}' found for transactionId: {}", cookieName, transactionId);
            } else {
                log.debug("Cookie '{}' not found for transactionId: {}", cookieName, transactionId);
            }

            return foundCookie;

        } catch (TransactionNotFoundException e) {
            throw e;
        } catch (DataAccessException e) {
            log.error("Database error retrieving cookie '{}' for transactionId: {}", cookieName, transactionId, e);
            throw new ScanExecutionException("Database error during cookie retrieval", e);
        } catch (Exception e) {
            log.error("Unexpected error retrieving cookie '{}' for transactionId: {}", cookieName, transactionId, e);
            throw new ScanExecutionException("Unexpected error during cookie retrieval", e);
        }
    }

    /**
     * Get all cookies for a transaction
     */
    public List<CookieEntity> getAllCookies(String transactionId)
            throws TransactionNotFoundException, ScanExecutionException, UrlValidationException {

        if (transactionId == null || transactionId.trim().isEmpty()) {
            throw new UrlValidationException("Transaction ID is required");
        }

        log.debug("Retrieving all cookies for transactionId: {}", transactionId);

        try {
            Optional<ScanResultEntity> scanResultOpt = findScanResultByTransactionId(transactionId);

            if (scanResultOpt.isEmpty()) {
                throw new TransactionNotFoundException(transactionId);
            }

            ScanResultEntity scanResult = scanResultOpt.get();
            List<CookieEntity> cookies = scanResult.getCookies();

            if (cookies == null) {
                log.debug("No cookies found for transaction: {}", transactionId);
                return List.of();
            }

            log.debug("Found {} cookies for transactionId: {}", cookies.size(), transactionId);
            return cookies;

        } catch (TransactionNotFoundException e) {
            throw e;
        } catch (DataAccessException e) {
            log.error("Database error retrieving cookies for transactionId: {}", transactionId, e);
            throw new ScanExecutionException("Database error during cookie retrieval", e);
        } catch (Exception e) {
            log.error("Unexpected error retrieving cookies for transactionId: {}", transactionId, e);
            throw new ScanExecutionException("Unexpected error during cookie retrieval", e);
        }
    }

    // Private helper methods

    private Optional<ScanResultEntity> findScanResultByTransactionId(String transactionId) throws DataAccessException {
        try {
            return scanResultRepository.findByTransactionId(transactionId);
        } catch (Exception e) {
            log.error("Database error finding scan result for transactionId: {}", transactionId, e);
            throw new DataAccessException("Failed to query database for transaction: " + transactionId, e) {};
        }
    }

    private void validateScanStatus(ScanResultEntity scanResult, String transactionId) throws UrlValidationException {
        if (!"COMPLETED".equals(scanResult.getStatus())) {
            String message = "Cannot update cookie for incomplete scan. Status: " + scanResult.getStatus();
            log.warn("Invalid scan status for transactionId {}: {}", transactionId, scanResult.getStatus());
            throw new UrlValidationException("Scan must be completed before updating cookies. Current status: " + scanResult.getStatus());
        }
    }

    private CookieEntity findAndUpdateCookie(ScanResultEntity scanResult, CookieUpdateRequest updateRequest, String transactionId) throws CookieNotFoundException, UrlValidationException {
        List<CookieEntity> cookies = scanResult.getCookies();

        if (cookies == null || cookies.isEmpty()) {
            String message = "No cookies found for transaction: " + transactionId;
            log.warn("No cookies available for update in transactionId: {}", transactionId);
            throw new UrlValidationException("No cookies available for this transaction");
        }

        Optional<CookieEntity> cookieToUpdate = cookies.stream()
                .filter(cookie -> updateRequest.getCookieName().equals(cookie.getName()))
                .findFirst();

        if (cookieToUpdate.isEmpty()) {
            String message = "Cookie '" + updateRequest.getCookieName() + "' not found in transaction: " + transactionId;
            log.warn("Cookie '{}' not found in transactionId: {}", updateRequest.getCookieName(), transactionId);
            throw new CookieNotFoundException("Cookie '" + updateRequest.getCookieName() + "' not found in transaction: " + transactionId);
        }

        // Update the cookie
        CookieEntity cookie = cookieToUpdate.get();
        String oldCategory = cookie.getCategory();
        String oldDescription = cookie.getDescription();

        // Update only if new values are provided and different
        if (updateRequest.getCategory() != null && !updateRequest.getCategory().equals(oldCategory)) {
            cookie.setCategory(updateRequest.getCategory());
        }
        if (updateRequest.getDescription() != null && !updateRequest.getDescription().equals(oldDescription)) {
            cookie.setDescription(updateRequest.getDescription());
        }

        log.debug("Updated cookie '{}': Category {} -> {}, Description {} -> {}",
                updateRequest.getCookieName(), oldCategory, cookie.getCategory(),
                oldDescription, cookie.getDescription());

        return cookie;
    }

    private void saveScanResult(ScanResultEntity scanResult, String transactionId) throws DataAccessException {
        try {
            scanResultRepository.save(scanResult);
        } catch (Exception e) {
            log.error("Failed to save updated scan result for transactionId: {}", transactionId, e);
            throw new DataAccessException("Failed to save cookie updates to database", e) {};
        }
    }
}