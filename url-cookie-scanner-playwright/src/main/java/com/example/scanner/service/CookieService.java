package com.example.scanner.service;

import com.example.scanner.constants.ErrorCodes;
import com.example.scanner.dto.AddCookieRequest;
import com.example.scanner.dto.AddCookieResponse;
import com.example.scanner.dto.CookieUpdateRequest;
import com.example.scanner.dto.CookieUpdateResponse;
import com.example.scanner.entity.CookieEntity;
import com.example.scanner.entity.ScanResultEntity;
import com.example.scanner.enums.SameSite;
import com.example.scanner.enums.Source;
import com.example.scanner.exception.CookieNotFoundException;
import com.example.scanner.exception.ScanExecutionException;
import com.example.scanner.exception.TransactionNotFoundException;
import com.example.scanner.exception.UrlValidationException;
import com.example.scanner.repository.ScanResultRepository;
import com.example.scanner.util.UrlAndCookieUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

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
            throw new UrlValidationException(ErrorCodes.EMPTY_ERROR,
                    "Cookie name is required for update",
                    "CookieUpdateRequest validation failed: cookieName is null or empty"
            );
        }

        if (updateRequest.getCategory() == null || updateRequest.getCategory().trim().isEmpty()) {
            throw new UrlValidationException(ErrorCodes.EMPTY_ERROR,
                    "Category is required for update",
                    "CookieUpdateRequest validation failed: Category is null or empty"
            );
        }

        if (updateRequest.getDescription() == null || updateRequest.getDescription().trim().isEmpty()) {
            throw new UrlValidationException(ErrorCodes.EMPTY_ERROR,
                    "Description is required for update",
                    "CookieUpdateRequest validation failed: Description is null or empty"
            );
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
        } catch (DataAccessException e) {
            log.error("Database error during cookie update for transactionId: {}", transactionId, e);
            throw new ScanExecutionException("Database error during cookie update: " + e.getMessage());
        } catch (Exception e) {
            log.error("Unexpected error updating cookie '{}' for transactionId: {}",
                    updateRequest.getCookieName(), transactionId, e);
            throw new ScanExecutionException("Unexpected error during cookie update: " + e.getMessage());
        }
    }

    /**
     * Get a specific cookie details from a transaction
     */
    public Optional<CookieEntity> getCookie(String transactionId, String cookieName)
            throws TransactionNotFoundException, ScanExecutionException, UrlValidationException {

        if (transactionId == null || transactionId.trim().isEmpty()) {
            throw new UrlValidationException(ErrorCodes.EMPTY_ERROR,
                    "Transaction ID is required",
                    "Method parameter validation failed: transactionId is null or empty"
            );
        }

        if (cookieName == null || cookieName.trim().isEmpty()) {
            throw new UrlValidationException(ErrorCodes.EMPTY_ERROR,
                    "Cookie name is required",
                    "Method parameter validation failed: cookieName is null or empty"
            );
        }

        log.debug("Retrieving cookie '{}' for transactionId: {}", cookieName, transactionId);

        try {
            Optional<ScanResultEntity> scanResultOpt = findScanResultByTransactionId(transactionId);

            if (scanResultOpt.isEmpty()) {
                throw new TransactionNotFoundException(transactionId);
            }

            ScanResultEntity scanResult = scanResultOpt.get();

            // Search across all subdomains since cookies are now grouped
            if (scanResult.getCookiesBySubdomain() == null || scanResult.getCookiesBySubdomain().isEmpty()) {
                log.debug("No cookies found for transaction: {}", transactionId);
                return Optional.empty();
            }

            // Search through all subdomain cookie lists
            for (List<CookieEntity> cookies : scanResult.getCookiesBySubdomain().values()) {
                Optional<CookieEntity> foundCookie = cookies.stream()
                        .filter(cookie -> cookieName.equals(cookie.getName()))
                        .findFirst();

                if (foundCookie.isPresent()) {
                    log.debug("Cookie '{}' found for transactionId: {}", cookieName, transactionId);
                    return foundCookie;
                }
            }

            log.debug("Cookie '{}' not found for transactionId: {}", cookieName, transactionId);
            return Optional.empty();

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
            throw new UrlValidationException(ErrorCodes.EMPTY_ERROR,
                    "Transaction ID is required",
                    "Method parameter validation failed: transactionId is null or empty"
            );
        }

        log.debug("Retrieving all cookies for transactionId: {}", transactionId);

        try {
            Optional<ScanResultEntity> scanResultOpt = findScanResultByTransactionId(transactionId);

            if (scanResultOpt.isEmpty()) {
                throw new TransactionNotFoundException(transactionId);
            }

            ScanResultEntity scanResult = scanResultOpt.get();

            // Flatten all cookies from all subdomains into one list
            List<CookieEntity> allCookies = new ArrayList<>();
            if (scanResult.getCookiesBySubdomain() != null) {
                for (List<CookieEntity> subdomainCookies : scanResult.getCookiesBySubdomain().values()) {
                    allCookies.addAll(subdomainCookies);
                }
            }

            log.debug("Found {} cookies for transactionId: {}", allCookies.size(), transactionId);
            return allCookies;

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
            throw new UrlValidationException(ErrorCodes.INVALID_STATE_ERROR,
                    "Scan must be completed before updating cookies. Current status: " + scanResult.getStatus(),
                    "Scan status validation failed for transactionId: " + transactionId + ", current status: " + scanResult.getStatus()
            );
        }
    }

    private CookieEntity findAndUpdateCookie(ScanResultEntity scanResult, CookieUpdateRequest updateRequest, String transactionId) throws CookieNotFoundException, UrlValidationException {
        // Get all cookies from all subdomains for searching
        List<CookieEntity> allCookies = new ArrayList<>();
        if (scanResult.getCookiesBySubdomain() != null) {
            for (List<CookieEntity> subdomainCookies : scanResult.getCookiesBySubdomain().values()) {
                allCookies.addAll(subdomainCookies);
            }
        }

        if (allCookies.isEmpty()) {
            String message = "No cookies found for transaction: " + transactionId;
            log.warn("No cookies available for update in transactionId: {}", transactionId);
            throw new UrlValidationException(ErrorCodes.NO_COOKIES_FOUND,
                    "No cookies available for this transaction",
                    "Cookie list validation failed: transaction " + transactionId + " has null or empty cookie list"
            );
        }

        Optional<CookieEntity> cookieToUpdate = allCookies.stream()
                .filter(cookie -> updateRequest.getCookieName().equals(cookie.getName()))
                .findFirst();

        if (cookieToUpdate.isEmpty()) {
            String message = "Cookie '" + updateRequest.getCookieName() + "' not found in transaction: " + transactionId;
            log.warn("Cookie '{}' not found in transactionId: {}", updateRequest.getCookieName(), transactionId);
            throw new CookieNotFoundException(updateRequest.getCookieName(), transactionId);
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

    @Transactional
    public AddCookieResponse addCookie(String transactionId, AddCookieRequest addRequest)
            throws TransactionNotFoundException, ScanExecutionException, UrlValidationException {

        if (transactionId == null || transactionId.trim().isEmpty()) {
            throw new IllegalArgumentException("Transaction ID cannot be null or empty");
        }

        if (addRequest == null) {
            throw new UrlValidationException(ErrorCodes.EMPTY_ERROR,
                    "Cookie information is required",
                    "AddCookieRequest is null"
            );
        }

        log.info("Adding cookie '{}' to transactionId: {} in subdomain: {}",
                addRequest.getName(), transactionId, addRequest.getSubdomainName());

        try {
            // Find the scan result by transaction ID
            Optional<ScanResultEntity> scanResultOpt = findScanResultByTransactionId(transactionId);

            if (scanResultOpt.isEmpty()) {
                throw new TransactionNotFoundException(transactionId);
            }

            ScanResultEntity scanResult = scanResultOpt.get();

            // Validate that we can add cookies (scan doesn't need to be completed for manual additions)
            if ("FAILED".equals(scanResult.getStatus())) {
                throw new UrlValidationException(ErrorCodes.INVALID_STATE_ERROR,
                        "Cannot add cookies to a failed scan",
                        "Scan status validation failed for transactionId: " + transactionId + ", status: FAILED"
                );
            }

            // Validate subdomain name
            String subdomainName = validateAndNormalizeSubdomainName(addRequest.getSubdomainName());
            addRequest.setSubdomainName(subdomainName);

            // Validate cookie domain against scan URL
            validateCookieDomainAgainstScanUrl(scanResult.getUrl(), addRequest.getDomain(), subdomainName);

            // Check for duplicate cookie
            if (cookieExists(scanResult, addRequest)) {
                throw new UrlValidationException(ErrorCodes.DUPLICATE_ERROR,
                        "Cookie already exists in this subdomain",
                        "Duplicate cookie: " + addRequest.getName() + " in subdomain: " + subdomainName
                );
            }

            // Create the new cookie entity
            CookieEntity newCookie = createCookieEntityFromRequest(addRequest, scanResult.getUrl());

            // Add cookie to the subdomain group
            addCookieToSubdomain(scanResult, newCookie, subdomainName);

            // Save the updated scan result
            saveScanResult(scanResult, transactionId);

            log.info("Successfully added cookie '{}' to transactionId: {} in subdomain: '{}'",
                    addRequest.getName(), transactionId, subdomainName);

            return AddCookieResponse.success(transactionId, addRequest.getName(),
                    addRequest.getDomain(), subdomainName);

        } catch (TransactionNotFoundException | UrlValidationException e) {
            log.warn("Validation failed for adding cookie '{}' to transactionId: {}: {}",
                    addRequest.getName(), transactionId, e.getMessage());
            throw e;
        } catch (DataAccessException e) {
            log.error("Database error during cookie addition for transactionId: {}", transactionId, e);
            throw new ScanExecutionException("Database error during cookie addition: " + e.getMessage());
        } catch (Exception e) {
            log.error("Unexpected error adding cookie '{}' to transactionId: {}",
                    addRequest.getName(), transactionId, e);
            throw new ScanExecutionException("Unexpected error during cookie addition: " + e.getMessage());
        }
    }

// Helper methods

    private String validateAndNormalizeSubdomainName(String subdomainName) throws UrlValidationException {
        if (subdomainName == null || subdomainName.trim().isEmpty()) {
            return "main";
        }

        String normalized = subdomainName.trim().toLowerCase();

        // Validate subdomain name format
        if (!normalized.matches("^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?$") && !"main".equals(normalized)) {
            throw new UrlValidationException(ErrorCodes.INVALID_FORMAT_ERROR,
                    "Invalid subdomain name format",
                    "Subdomain name must contain only lowercase letters, numbers, and hyphens: " + subdomainName
            );
        }

        return normalized;
    }

    private void validateCookieDomainAgainstScanUrl(String scanUrl, String cookieDomain, String subdomainName)
            throws UrlValidationException {
        try {
            String scanRootDomain = UrlAndCookieUtil.extractRootDomain(scanUrl);

            // Clean cookie domain (remove leading dot if present)
            String cleanCookieDomain = cookieDomain.startsWith(".") ?
                    cookieDomain.substring(1) : cookieDomain;

            String cookieRootDomain = UrlAndCookieUtil.extractRootDomain(cleanCookieDomain);

            // Cookie domain must belong to the same root domain as the scan URL
            if (!scanRootDomain.equalsIgnoreCase(cookieRootDomain)) {
                throw new UrlValidationException(ErrorCodes.INVALID_FORMAT_ERROR,
                        "Cookie domain must belong to the same root domain as the scanned URL",
                        String.format("Cookie domain '%s' does not belong to scan domain '%s'",
                                cookieDomain, scanRootDomain)
                );
            }

            log.debug("Cookie domain validation passed: {} belongs to scan domain {}",
                    cookieDomain, scanRootDomain);

        } catch (Exception e) {
            throw new UrlValidationException(ErrorCodes.INVALID_FORMAT_ERROR,
                    "Invalid cookie domain format",
                    "Cookie domain validation failed: " + e.getMessage()
            );
        }
    }

    private boolean cookieExists(ScanResultEntity scanResult, AddCookieRequest addRequest) {
        if (scanResult.getCookiesBySubdomain() == null) {
            return false;
        }

        List<CookieEntity> subdomainCookies = scanResult.getCookiesBySubdomain()
                .get(addRequest.getSubdomainName());

        if (subdomainCookies == null) {
            return false;
        }

        return subdomainCookies.stream()
                .anyMatch(cookie ->
                        cookie.getName().equals(addRequest.getName()) &&
                                Objects.equals(cookie.getDomain(), addRequest.getDomain())
                );
    }

    private CookieEntity createCookieEntityFromRequest(AddCookieRequest request, String scanUrl) {
        // Parse enums
        SameSite sameSite = null;
        if (request.getSameSite() != null) {
            try {
                sameSite = SameSite.valueOf(request.getSameSite().toUpperCase());
            } catch (IllegalArgumentException e) {
                sameSite = SameSite.LAX; // Default fallback
            }
        }

        Source source = null;
        if (request.getSource() != null) {
            try {
                source = Source.valueOf(request.getSource().toUpperCase());
            } catch (IllegalArgumentException e) {
                source = Source.FIRST_PARTY; // Default fallback
            }
        }

        return new CookieEntity(
                request.getName(),
                scanUrl,
                request.getDomain(),
                request.getPath() != null ? request.getPath() : "/",
                request.getExpires(),
                request.isSecure(),
                request.isHttpOnly(),
                sameSite,
                source,
                request.getCategory(),
                request.getDescription(),
                request.getDescription_gpt(),
                request.getSubdomainName()
        );
    }

    private void addCookieToSubdomain(ScanResultEntity scanResult, CookieEntity cookie, String subdomainName) {
        // Initialize the map if it doesn't exist
        if (scanResult.getCookiesBySubdomain() == null) {
            scanResult.setCookiesBySubdomain(new HashMap<>());
        }

        // Get or create the subdomain cookie list
        List<CookieEntity> subdomainCookies = scanResult.getCookiesBySubdomain()
                .computeIfAbsent(subdomainName, k -> new ArrayList<>());

        // Add the cookie
        subdomainCookies.add(cookie);

        log.debug("Added cookie '{}' to subdomain '{}'. Subdomain now has {} cookies.",
                cookie.getName(), subdomainName, subdomainCookies.size());
    }
}