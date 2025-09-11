package com.example.scanner.util;

import com.example.scanner.exception.UrlValidationException;
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
                    // Validate subdomain URL format
                    UrlAndCookieUtil.ValidationResult subdomainValidation =
                            UrlAndCookieUtil.validateUrlForScanning(trimmedSubdomain);

                    if (!subdomainValidation.isValid()) {
                        invalidSubdomains.add(trimmedSubdomain + " - " + subdomainValidation.getErrorMessage());
                        continue;
                    }

                    // Extract root domain from subdomain
                    String subdomainRootDomain = UrlAndCookieUtil.extractRootDomain(trimmedSubdomain);

                    // Check if subdomain belongs to the same root domain
                    if (!mainRootDomain.equalsIgnoreCase(subdomainRootDomain)) {
                        invalidSubdomains.add(trimmedSubdomain + " - Does not belong to domain: " + mainRootDomain);
                        continue;
                    }

                    // Check if it's actually a subdomain (not the same as main domain)
                    String mainHost = extractHost(mainUrl);
                    String subdomainHost = extractHost(trimmedSubdomain);

                    if (mainHost.equalsIgnoreCase(subdomainHost)) {
                        invalidSubdomains.add(trimmedSubdomain + " - Subdomain cannot be the same as the main URL");
                        continue;
                    }

                    validatedSubdomains.add(subdomainValidation.getNormalizedUrl());
                    log.info("Valid subdomain: {} (root: {})", trimmedSubdomain, subdomainRootDomain);

                } catch (Exception e) {
                    invalidSubdomains.add(trimmedSubdomain + " - Validation error: " + e.getMessage());
                }
            }

            if (!invalidSubdomains.isEmpty()) {
                String errorMessage = "Invalid subdomains found: " + String.join(", ", invalidSubdomains);
                return ValidationResult.invalid(errorMessage);
            }

            log.info("Validated {} subdomains for domain: {}", validatedSubdomains.size(), mainRootDomain);
            return ValidationResult.valid(validatedSubdomains);

        } catch (Exception e) {
            log.error("Error during subdomain validation: {}", e.getMessage(), e);
            return ValidationResult.invalid("Subdomain validation failed: " + e.getMessage());
        }
    }

    /**
     * Extract host from URL
     */
    private static String extractHost(String url) throws URISyntaxException {
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            url = "https://" + url;
        }
        return new URI(url).getHost().toLowerCase();
    }

    /**
     * Check if a URL is a subdomain of the given root domain
     */
    public static boolean isSubdomainOf(String url, String rootDomain) {
        try {
            String urlRootDomain = UrlAndCookieUtil.extractRootDomain(url);
            return rootDomain.equalsIgnoreCase(urlRootDomain);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Extract subdomain name from full URL
     * Example: "api.example.com" -> "api"
     */
    public static String extractSubdomainName(String url, String rootDomain) {
        try {
            String host = extractHost(url);

            // Remove root domain from host to get subdomain part
            if (host.endsWith("." + rootDomain)) {
                String subdomainPart = host.substring(0, host.length() - rootDomain.length() - 1);

                // Handle cases like "www" or "api.v1"
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