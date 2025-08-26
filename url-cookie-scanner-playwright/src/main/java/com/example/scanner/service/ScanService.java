package com.example.scanner.service;

import com.example.scanner.dto.CookieCategorizationResponse;
import com.example.scanner.dto.CookieDto;
import com.example.scanner.entity.CookieEntity;
import com.example.scanner.entity.ScanResultEntity;
import com.example.scanner.enums.SameSite;
import com.example.scanner.enums.ScanStatus;
import com.example.scanner.enums.Source;
import com.example.scanner.exception.ScanExecutionException;
import com.example.scanner.exception.ScannerException;
import com.example.scanner.exception.UrlValidationException;
import com.example.scanner.mapper.ScanResultMapper;
import com.example.scanner.repository.ScanResultRepository;
import com.example.scanner.util.UrlAndCookieUtil;
import com.example.scanner.util.UrlAndCookieUtil.ValidationResult;
import com.microsoft.playwright.*;
import com.microsoft.playwright.options.WaitUntilState;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class ScanService {

    private static final Logger log = LoggerFactory.getLogger(ScanService.class);

    private final ScanResultRepository repository;
    private final CookieCategorizationService cookieCategorizationService;

    @Lazy
    @Autowired
    private ScanService self;

    public ScanService(ScanResultRepository repository, CookieCategorizationService cookieCategorizationService) {
        this.repository = repository;
        this.cookieCategorizationService = cookieCategorizationService;
    }

    public String startScan(String url) throws UrlValidationException, ScanExecutionException {
        log.info("Received request to scan URL: {}", url);

        try {
            ValidationResult validationResult = UrlAndCookieUtil.validateUrlForScanning(url);
            if (!validationResult.isValid()) {
                throw new UrlValidationException(validationResult.getErrorMessage());
            }

            String normalizedUrl = validationResult.getNormalizedUrl();
            log.info("URL validation passed. Normalized URL: {}", normalizedUrl);

            String transactionId = UUID.randomUUID().toString();
            ScanResultEntity result = new ScanResultEntity();
            result.setId(transactionId);
            result.setTransactionId(transactionId);
            result.setStatus(ScanStatus.PENDING.name());
            result.setUrl(normalizedUrl);

            try {
                repository.save(result);
            } catch (Exception e) {
                log.error("Failed to save scan result to database", e);
                throw new ScanExecutionException("Failed to initialize scan", e);
            }

            log.info("Created new scan with transactionId={} for URL={}", transactionId, normalizedUrl);

            // Run async scan with normalized URL
            self.runScanAsync(transactionId, normalizedUrl);

            return transactionId;

        } catch (UrlValidationException e) {
            throw e; // Re-throw custom exceptions
        } catch (Exception e) {
            log.error("Unexpected error during scan initialization", e);
            throw new ScanExecutionException("Failed to start scan", e);
        }
    }

    @Async
    public void runScanAsync(String transactionId, String url) {
        log.info("Starting DPDPA-compliant cookie scan for transactionId={} URL={}", transactionId, url);

        ScanResultEntity result = null;
        try {
            result = repository.findById(transactionId)
                    .orElseThrow(() -> new IllegalStateException("Scan result not found for transactionId: " + transactionId));

            result.setStatus(ScanStatus.RUNNING.name());
            repository.save(result);

            // Execute scan with proper error handling
            List<CookieEntity> cookies = performScan(url, transactionId);

            result.setStatus(ScanStatus.COMPLETED.name());
            result.setCookies(cookies);
            result.setUrl(url);
            repository.save(result);

            log.info("DPDPA-compliant scan COMPLETED for transactionId={} with {} cookies",
                    transactionId, cookies.size());

        } catch (Exception e) {
            log.error("DPDPA scan FAILED for transactionId={} URL={} due to error: {}",
                    transactionId, url, e.getMessage(), e);

            if (result != null) {
                result.setStatus(ScanStatus.FAILED.name());
                result.setErrorMessage(getErrorMessage(e));
                try {
                    repository.save(result);
                } catch (Exception saveEx) {
                    log.error("Failed to save error status for transactionId={}", transactionId, saveEx);
                }
            }
        }
    }

    private List<CookieEntity> performScan(String url, String transactionId) throws ScanExecutionException {
        Playwright playwright = null;
        Browser browser = null;
        BrowserContext context = null;

        try {
            playwright = Playwright.create();

            BrowserType.LaunchOptions launchOptions = new BrowserType.LaunchOptions()
                    .setHeadless(true)
                    .setTimeout(45000)
                    .setSlowMo(100);

            browser = playwright.chromium().launch(launchOptions);

            Browser.NewContextOptions contextOptions = new Browser.NewContextOptions()
                    .setUserAgent("CookieConsentCrawler/1.0 (DPDPA Compliance Scanner)")
                    .setViewportSize(1920, 1080)
                    .setIgnoreHTTPSErrors(false)
                    .setJavaScriptEnabled(true)
                    .setAcceptDownloads(false);

            context = browser.newContext(contextOptions);
            context.setDefaultTimeout(45000);
            context.setDefaultNavigationTimeout(45000);

            Page page = context.newPage();

            // Extract root domain for classification
            String siteRoot = UrlAndCookieUtil.extractRootDomain(url);
            log.debug("Site root domain: {}", siteRoot);

            // Cookie collection logic
            List<CookieDto> httpHeaderCookies = Collections.synchronizedList(new ArrayList<>());

            context.onResponse(response -> {
                try {
                    String setCookieHeader = response.headers().get("set-cookie");
                    if (setCookieHeader != null && !setCookieHeader.trim().isEmpty()) {
                        String responseUrl = response.url();
                        String responseRoot = UrlAndCookieUtil.extractRootDomain(responseUrl);

                        Source source = siteRoot.equalsIgnoreCase(responseRoot)
                                ? Source.FIRST_PARTY
                                : Source.THIRD_PARTY;

                        CookieDto parsedCookie = parseSetCookieHeader(setCookieHeader, url, responseRoot, source);
                        if (parsedCookie != null) {
                            httpHeaderCookies.add(parsedCookie);
                        }
                    }
                } catch (Exception e) {
                    log.warn("Error processing response for cookie extraction: {}", e.getMessage());
                }
            });

            log.debug("Navigating to URL: {}", url);

            Response response = page.navigate(url, new Page.NavigateOptions().setWaitUntil(WaitUntilState.NETWORKIDLE));

            if (response == null || !response.ok()) {
                throw new ScanExecutionException("Failed to load page. Status: " +
                        (response != null ? response.status() : "No response"), null);
            }

            // Wait for dynamic content
            page.waitForTimeout(5000);
            page.evaluate("window.scrollTo(0, document.body.scrollHeight)");
            page.waitForTimeout(2000);

            // Handle consent banners
            handleConsentBanners(page);

            // Collect cookies
            List<CookieDto> browserContextCookies = collectBrowserCookies(context, url, siteRoot);

            // Merge and deduplicate
            List<CookieDto> allCookieDtos = new ArrayList<>();
            allCookieDtos.addAll(browserContextCookies);
            allCookieDtos.addAll(httpHeaderCookies);

            Map<String, CookieDto> deduplicatedCookies = deduplicateCookies(allCookieDtos);

            // Categorize cookies with error handling
            categorizeCookies(deduplicatedCookies, transactionId);

            return deduplicatedCookies.values().stream()
                    .map(ScanResultMapper::cookieDtoToEntity)
                    .collect(Collectors.toList());

        } catch (PlaywrightException e) {
            throw new ScanExecutionException("Playwright error during scan: " + e.getMessage(), e);
        } catch (Exception e) {
            throw new ScanExecutionException("Unexpected error during scan: " + e.getMessage(), e);
        } finally {
            // Ensure proper cleanup
            cleanupResources(context, browser, playwright);
        }
    }

    private void handleConsentBanners(Page page) {
        try {
            // Look for common consent banner buttons and click them
            List<String> consentSelectors = Arrays.asList(
                    "[data-testid*='accept']", "[data-cy*='accept']",
                    "button[id*='accept']", "button[class*='accept']",
                    "[aria-label*='accept' i]", "[title*='accept' i]"
            );

            for (String selector : consentSelectors) {
                if (page.locator(selector).count() > 0) {
                    log.debug("Found consent button with selector: {}", selector);
                    page.locator(selector).first().click();
                    page.waitForTimeout(2000);
                    break;
                }
            }
        } catch (Exception e) {
            log.debug("No consent banners found or error clicking: {}", e.getMessage());
        }
    }

    private List<CookieDto> collectBrowserCookies(BrowserContext context, String url, String siteRoot) {
        return context.cookies().stream()
                .map(playwrightCookie -> {
                    try {
                        Instant expiry = playwrightCookie.expires != null ?
                                Instant.ofEpochSecond(playwrightCookie.expires.longValue()) : null;

                        String cookieDomain = playwrightCookie.domain;
                        Source source = determineSourceType(cookieDomain, siteRoot);
                        SameSite sameSite = parseSameSiteAttribute(playwrightCookie.sameSite);

                        return new CookieDto(
                                playwrightCookie.name,
                                url,
                                cookieDomain,
                                playwrightCookie.path != null ? playwrightCookie.path : "/",
                                expiry,
                                playwrightCookie.secure,
                                playwrightCookie.httpOnly,
                                sameSite,
                                source
                        );
                    } catch (Exception e) {
                        log.error("Error mapping Playwright cookie: {}", e.getMessage());
                        return null;
                    }
                })
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
    }

    private Map<String, CookieDto> deduplicateCookies(List<CookieDto> allCookieDtos) {
        return allCookieDtos.stream()
                .collect(Collectors.toMap(
                        cookie -> generateCookieKey(cookie.getName(), cookie.getDomain()),
                        cookie -> cookie,
                        (existing, duplicate) -> {
                            // Keep the most complete cookie
                            if (existing.getExpires() != null && duplicate.getExpires() == null) {
                                return existing;
                            } else if (existing.getExpires() == null && duplicate.getExpires() != null) {
                                return duplicate;
                            }
                            return existing;
                        }
                ));
    }

    private void categorizeCookies(Map<String, CookieDto> cookies, String transactionId) {
        try {
            List<String> cookieNames = cookies.values().stream()
                    .map(CookieDto::getName)
                    .collect(Collectors.toList());

            log.info("Calling cookie categorization API for {} unique cookies", cookieNames.size());
            Map<String, CookieCategorizationResponse> categoryMap =
                    cookieCategorizationService.categorizeCookies(cookieNames);

            for (CookieDto cookie : cookies.values()) {
                CookieCategorizationResponse categorization = categoryMap.get(cookie.getName());
                if (categorization != null) {
                    cookie.setCategory(categorization.getCategory());
                    cookie.setDescription(categorization.getDescription());
                } else {
                    cookie.setCategory("Unknown");
                    cookie.setDescription("Category not determined");
                }
            }
        } catch (Exception e) {
            log.error("Cookie categorization failed for transactionId={}: {}", transactionId, e.getMessage(), e);
            // Don't fail the entire scan, just mark cookies as uncategorized
            for (CookieDto cookie : cookies.values()) {
                cookie.setCategory("Unknown");
                cookie.setDescription("Categorization failed");
            }
        }
    }

    private CookieDto parseSetCookieHeader(String setCookieHeader, String scanUrl, String responseDomain, Source source) {
        try {
            String[] parts = setCookieHeader.split(";");
            if (parts.length == 0) return null;

            String[] nameValue = parts[0].trim().split("=", 2);
            if (nameValue.length != 2) return null;

            String name = nameValue[0].trim();
            String domain = responseDomain;
            String path = "/";
            Instant expires = null;
            boolean secure = false;
            boolean httpOnly = false;
            SameSite sameSite = null;

            for (int i = 1; i < parts.length; i++) {
                String attribute = parts[i].trim().toLowerCase();

                if (attribute.startsWith("domain=")) {
                    domain = attribute.substring(7);
                } else if (attribute.startsWith("path=")) {
                    path = attribute.substring(5);
                } else if (attribute.equals("secure")) {
                    secure = true;
                } else if (attribute.equals("httponly")) {
                    httpOnly = true;
                } else if (attribute.startsWith("samesite=")) {
                    String sameSiteValue = attribute.substring(9);
                    sameSite = parseSameSiteValue(sameSiteValue);
                }
            }

            return new CookieDto(name, scanUrl, domain, path, expires, secure, httpOnly, sameSite, source);

        } catch (Exception e) {
            log.warn("Error parsing Set-Cookie header: {}", e.getMessage());
            return null;
        }
    }

    private Source determineSourceType(String cookieDomain, String scanDomain) {
        if (cookieDomain == null || scanDomain == null) {
            return Source.UNKNOWN;
        }

        String cleanCookieDomain = cookieDomain.startsWith(".") ?
                cookieDomain.substring(1) : cookieDomain;

        String cookieRoot = UrlAndCookieUtil.extractRootDomain(cleanCookieDomain);
        String scanRoot = UrlAndCookieUtil.extractRootDomain(scanDomain);

        return scanRoot.equalsIgnoreCase(cookieRoot) ? Source.FIRST_PARTY : Source.THIRD_PARTY;
    }

    private SameSite parseSameSiteAttribute(Object sameSiteObj) {
        if (sameSiteObj == null) return null;

        try {
            return SameSite.valueOf(sameSiteObj.toString().toUpperCase());
        } catch (IllegalArgumentException e) {
            log.warn("Unknown SameSite value: {}", sameSiteObj);
            return SameSite.LAX;
        }
    }

    private SameSite parseSameSiteValue(String sameSiteValue) {
        if (sameSiteValue == null) return null;

        switch (sameSiteValue.toLowerCase()) {
            case "strict": return SameSite.STRICT;
            case "lax": return SameSite.LAX;
            case "none": return SameSite.NONE;
            default: return SameSite.LAX;
        }
    }

    private String generateCookieKey(String name, String domain) {
        return name + "@" + (domain != null ? domain.toLowerCase() : "");
    }

    private void cleanupResources(BrowserContext context, Browser browser, Playwright playwright) {
        try {
            if (context != null) context.close();
        } catch (Exception e) {
            log.warn("Error closing browser context: {}", e.getMessage());
        }

        try {
            if (browser != null) browser.close();
        } catch (Exception e) {
            log.warn("Error closing browser: {}", e.getMessage());
        }

        try {
            if (playwright != null) playwright.close();
        } catch (Exception e) {
            log.warn("Error closing playwright: {}", e.getMessage());
        }
    }

    private String getErrorMessage(Exception e) {
        if (e instanceof ScannerException) {
            return ((ScannerException) e).getUserMessage();
        }
        return "An unexpected error occurred during scanning";
    }
}