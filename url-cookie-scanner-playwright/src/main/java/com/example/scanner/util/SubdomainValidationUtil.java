package com.example.scanner.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.List;

public class SubdomainValidationUtil {

    private static final Logger log = LoggerFactory.getLogger(SubdomainValidationUtil.class);

    /**
     * Validates that all subdomains belong to the same root domain as the main URL
     */
    public static ValidationResult validateSubdomains(String mainUrl, List<String> subdomains) {
        try {
            if (subdomains == null || subdomains.isEmpty()) {
                return ValidationResult.valid(new ArrayList<>());
            }

            // Get root domain from main URL
            String mainRootDomain = UrlAndCookieUtil.extractRootDomain(mainUrl);
            if (mainRootDomain == null || mainRootDomain.isEmpty()) {
                return ValidationResult.invalid("Cannot extract root domain from main URL: " + mainUrl);
            }

            log.info("Main URL root domain: {}", mainRootDomain);

            List<String> validatedSubdomains = new ArrayList<>();
            List<String> invalidSubdomains = new ArrayList<>();

            for (String subdomain : subdomains) {
                if (subdomain == null || subdomain.trim().isEmpty()) {
                    invalidSubdomains.add("Empty subdomain");
                    continue;
                }

                String trimmedSubdomain = subdomain.trim();

                try {
                    // FIXED: Use the same comprehensive validation as main URL
                    UrlAndCookieUtil.ValidationResult subdomainValidation =
                            UrlAndCookieUtil.validateUrlForScanning(trimmedSubdomain);

                    if (!subdomainValidation.isValid()) {
                        invalidSubdomains.add(trimmedSubdomain + " - " + subdomainValidation.getErrorMessage());
                        continue;
                    }

                    // Use the normalized URL from validation
                    String normalizedSubdomainUrl = subdomainValidation.getNormalizedUrl();

                    // Extract root domain from validated subdomain
                    String subdomainRootDomain = UrlAndCookieUtil.extractRootDomain(normalizedSubdomainUrl);

                    // Check if subdomain belongs to the same root domain
                    if (!mainRootDomain.equalsIgnoreCase(subdomainRootDomain)) {
                        invalidSubdomains.add(trimmedSubdomain + " - Does not belong to domain: " + mainRootDomain);
                        continue;
                    }

                    // Check if it's actually a subdomain (not the same as main domain)
                    String mainHost = extractHostSafely(mainUrl);
                    String subdomainHost = extractHostSafely(normalizedSubdomainUrl);

                    if (mainHost != null && mainHost.equalsIgnoreCase(subdomainHost)) {
                        invalidSubdomains.add(trimmedSubdomain + " - Subdomain cannot be the same as the main URL");
                        continue;
                    }

                    validatedSubdomains.add(normalizedSubdomainUrl);
                    log.info("Valid subdomain: {} -> {} (root: {})", trimmedSubdomain, normalizedSubdomainUrl, subdomainRootDomain);

                } catch (Exception e) {
                    log.warn("Error validating subdomain {}: {}", trimmedSubdomain, e.getMessage());
                    invalidSubdomains.add(trimmedSubdomain + " - Validation error: " + e.getMessage());
                }
            }

            if (!invalidSubdomains.isEmpty()) {
                String errorMessage = "Invalid subdomains found: " + String.join(", ", invalidSubdomains);
                return ValidationResult.invalid(errorMessage);
            }

            log.info("Successfully validated {} subdomains for domain: {}", validatedSubdomains.size(), mainRootDomain);
            return ValidationResult.valid(validatedSubdomains);

        } catch (Exception e) {
            log.error("Error during subdomain validation: {}", e.getMessage(), e);
            return ValidationResult.invalid("Subdomain validation failed: " + e.getMessage());
        }
    }

    /**
     * FIXED: Extract host from URL with proper protocol handling
     */
    private static String extractHostSafely(String url) {
        try {
            String normalizedUrl;

            // Handle protocol properly (same logic as main URL validation)
            if (url.contains("://")) {
                String protocol = url.substring(0, url.indexOf("://")).toLowerCase();
                if (!protocol.equals("http") && !protocol.equals("https")) {
                    return null; // Invalid protocol
                }
                normalizedUrl = url;
            } else {
                normalizedUrl = "https://" + url;
            }

            URI uri = new URI(normalizedUrl);
            String host = uri.getHost();
            return host != null ? host.toLowerCase() : null;

        } catch (URISyntaxException e) {
            log.warn("Failed to extract host from URL: {} - {}", url, e.getMessage());
            return null;
        }
    }

    /**
     * Check if a URL is a subdomain of the given root domain
     */
    public static boolean isSubdomainOf(String url, String rootDomain) {
        try {
            // Use comprehensive validation first
            UrlAndCookieUtil.ValidationResult validation = UrlAndCookieUtil.validateUrlForScanning(url);
            if (!validation.isValid()) {
                return false;
            }

            String urlRootDomain = UrlAndCookieUtil.extractRootDomain(validation.getNormalizedUrl());
            return rootDomain.equalsIgnoreCase(urlRootDomain);
        } catch (Exception e) {
            log.warn("Error checking if {} is subdomain of {}: {}", url, rootDomain, e.getMessage());
            return false;
        }
    }

    /**
     * Extract subdomain name from full URL
     * Example: "api.example.com" -> "api"
     */
    public static String extractSubdomainName(String url, String rootDomain) {
        try {
            String host = extractHostSafely(url);
            if (host == null) {
                return "unknown";
            }

            // Remove root domain from host to get subdomain part
            if (host.endsWith("." + rootDomain)) {
                String subdomainPart = host.substring(0, host.length() - rootDomain.length() - 1);
                return subdomainPart.isEmpty() ? "main" : subdomainPart;
            }

            // If host is exactly the root domain
            if (host.equalsIgnoreCase(rootDomain)) {
                return "main";
            }

            return "unknown";

        } catch (Exception e) {
            log.warn("Error extracting subdomain name from {}: {}", url, e.getMessage());
            return "unknown";
        }
    }

    /**
     * Additional utility: Validate subdomain naming conventions
     */
    public static boolean isValidSubdomainName(String subdomainName) {
        if (subdomainName == null || subdomainName.trim().isEmpty()) {
            return false;
        }

        // Check for valid subdomain naming (letters, numbers, hyphens)
        return subdomainName.matches("^[a-zA-Z0-9]([a-zA-Z0-9-]*[a-zA-Z0-9])?$")
                && subdomainName.length() <= 63;
    }

    /**
     * Result class for subdomain validation
     */
    public static class ValidationResult {
        private final boolean valid;
        private final List<String> validatedSubdomains;
        private final String errorMessage;

        private ValidationResult(boolean valid, List<String> validatedSubdomains, String errorMessage) {
            this.valid = valid;
            this.validatedSubdomains = validatedSubdomains;
            this.errorMessage = errorMessage;
        }

        public static ValidationResult valid(List<String> validatedSubdomains) {
            return new ValidationResult(true, validatedSubdomains, null);
        }

        public static ValidationResult invalid(String errorMessage) {
            return new ValidationResult(false, null, errorMessage);
        }

        public boolean isValid() {
            return valid;
        }

        public List<String> getValidatedSubdomains() {
            return validatedSubdomains;
        }

        public String getErrorMessage() {
            return errorMessage;
        }
    }
}