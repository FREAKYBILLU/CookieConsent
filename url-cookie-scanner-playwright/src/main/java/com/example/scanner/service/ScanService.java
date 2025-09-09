package com.example.scanner.service;

import com.example.scanner.config.ScannerConfigurationProperties;
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
import com.example.scanner.util.CookieDetectionUtil;
import com.example.scanner.util.UrlAndCookieUtil;
import com.example.scanner.util.UrlAndCookieUtil.ValidationResult;
import com.example.scanner.util.SubdomainValidationUtil;
import com.microsoft.playwright.*;
import com.microsoft.playwright.options.Cookie;
import com.microsoft.playwright.options.WaitUntilState;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.net.URL;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Service
public class ScanService {

    private static final Logger log = LoggerFactory.getLogger(ScanService.class);

    private final ScanResultRepository repository;
    private final CookieCategorizationService cookieCategorizationService;
    private final ScannerConfigurationProperties config;
    private final CookieScanMetrics metrics;

    @Lazy
    @Autowired
    private ScanService self;

    public ScanService(ScanResultRepository repository,
                       CookieCategorizationService cookieCategorizationService,
                       ScannerConfigurationProperties config,
                       CookieScanMetrics metrics) {
        this.repository = repository;
        this.cookieCategorizationService = cookieCategorizationService;
        this.config = config;
        this.metrics = metrics;
    }

    // NEW: Main method with subdomain support
    public String startScan(String url, List<String> subdomains) throws UrlValidationException, ScanExecutionException {
        log.info("Received request to scan URL: {} with {} subdomains", url, subdomains != null ? subdomains.size() : 0);

        try {
            ValidationResult validationResult = UrlAndCookieUtil.validateUrlForScanning(url);
            if (!validationResult.isValid()) {
                throw new UrlValidationException(validationResult.getErrorMessage());
            }

            String normalizedUrl = validationResult.getNormalizedUrl();
            log.info("URL validation passed. Normalized URL: {}", normalizedUrl);

            // Validate subdomains if provided
            List<String> validatedSubdomains = new ArrayList<>();
            if (subdomains != null && !subdomains.isEmpty()) {
                SubdomainValidationUtil.ValidationResult subdomainValidation =
                        SubdomainValidationUtil.validateSubdomains(normalizedUrl, subdomains);

                if (!subdomainValidation.isValid()) {
                    throw new UrlValidationException("Subdomain validation failed: " + subdomainValidation.getErrorMessage());
                }

                validatedSubdomains = subdomainValidation.getValidatedSubdomains();
                log.info("Subdomain validation passed. {} valid subdomains", validatedSubdomains.size());
            }

            String transactionId = UUID.randomUUID().toString();
            ScanResultEntity result = new ScanResultEntity();
            result.setId(transactionId);
            result.setTransactionId(transactionId);
            result.setStatus(ScanStatus.PENDING.name());
            result.setUrl(normalizedUrl);
            result.setCookies(new ArrayList<>());

            try {
                repository.save(result);
                metrics.recordScanStarted();
            } catch (Exception e) {
                log.error("Failed to save scan result to database", e);
                throw new ScanExecutionException("Failed to initialize scan", e);
            }

            log.info("Created new scan with transactionId={} for URL={} and {} subdomains",
                    transactionId, normalizedUrl, validatedSubdomains.size());

            // Pass subdomains to async method
            self.runScanAsync(transactionId, normalizedUrl, validatedSubdomains);

            return transactionId;

        } catch (UrlValidationException e) {
            throw e;
        } catch (Exception e) {
            log.error("Unexpected error during scan initialization", e);
            throw new ScanExecutionException("Failed to start scan", e);
        }
    }

    // ORIGINAL: Single URL method for backward compatibility
    public String startScan(String url) throws UrlValidationException, ScanExecutionException {
        return startScan(url, null);
    }

    // MODIFIED: Added subdomains parameter
    @Async
    public void runScanAsync(String transactionId, String url, List<String> subdomains) {
        log.info("Starting MAXIMUM COOKIE DETECTION scan for transactionId={} URL={} with {} subdomains",
                transactionId, url, subdomains != null ? subdomains.size() : 0);

        ScanPerformanceTracker.ScanMetrics scanMetrics = new ScanPerformanceTracker.ScanMetrics();
        long scanStartTime = System.currentTimeMillis();
        ScanResultEntity result = null;

        try {
            result = repository.findById(transactionId)
                    .orElseThrow(() -> new IllegalStateException("Scan result not found for transactionId: " + transactionId));

            result.setStatus(ScanStatus.RUNNING.name());
            repository.save(result);

            scanMetrics.setScanPhase("RUNNING");
            performMaximumCookieDetection(url, transactionId, scanMetrics, subdomains);

            result = repository.findById(transactionId).orElse(result);
            result.setStatus(ScanStatus.COMPLETED.name());
            repository.save(result);

            scanMetrics.setScanPhase("COMPLETED");
            scanMetrics.markCompleted();

            Duration totalDuration = Duration.ofMillis(System.currentTimeMillis() - scanStartTime);
            metrics.recordScanCompleted(totalDuration);
            scanMetrics.logSummary(transactionId);

            log.info("MAXIMUM DETECTION scan COMPLETED for transactionId={} with {} cookies in {}ms",
                    transactionId, result.getCookies().size(), totalDuration.toMillis());

        } catch (Exception e) {
            scanMetrics.markFailed(e.getMessage());
            scanMetrics.setScanPhase("FAILED");

            Duration totalDuration = Duration.ofMillis(System.currentTimeMillis() - scanStartTime);

            log.error("Maximum detection scan FAILED for transactionId={} URL={} after {}ms due to error: {}",
                    transactionId, url, totalDuration.toMillis(), e.getMessage(), e);

            if (result != null) {
                result.setStatus(ScanStatus.FAILED.name());
                result.setErrorMessage(getErrorMessage(e));
                try {
                    repository.save(result);
                } catch (Exception saveEx) {
                    log.error("Failed to save error status for transactionId={}", transactionId, saveEx);
                }
            }

            metrics.recordScanFailed(totalDuration);
            scanMetrics.logSummary(transactionId);
        }
    }

    // Backward compatibility overload
    @Async
    public void runScanAsync(String transactionId, String url) {
        runScanAsync(transactionId, url, null);
    }

    // MODIFIED: Added subdomains parameter
    private void performMaximumCookieDetection(String url, String transactionId,
                                               ScanPerformanceTracker.ScanMetrics scanMetrics,
                                               List<String> subdomains)
            throws ScanExecutionException {

        Playwright playwright = null;
        Browser browser = null;
        BrowserContext context = null;

        Map<String, CookieDto> discoveredCookies = new ConcurrentHashMap<>();
        Set<String> processedUrls = new HashSet<>();

        try {
            scanMetrics.setScanPhase("INITIALIZING_BROWSER");
            playwright = Playwright.create();

            BrowserType.LaunchOptions launchOptions = new BrowserType.LaunchOptions()
                    .setHeadless(true)
                    .setTimeout(90000)
                    .setSlowMo(200);

            browser = playwright.chromium().launch(launchOptions);

            Browser.NewContextOptions contextOptions = new Browser.NewContextOptions()
                    .setUserAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                    .setViewportSize(1920, 1080)
                    .setIgnoreHTTPSErrors(false)
                    .setJavaScriptEnabled(true)
                    .setAcceptDownloads(false)
                    .setPermissions(Arrays.asList("geolocation", "notifications"));

            context = browser.newContext(contextOptions);
            context.setDefaultTimeout(90000);
            context.setDefaultNavigationTimeout(90000);

            // ENHANCED response monitoring - ALL HEADERS
            context.onResponse(response -> {
                try {
                    scanMetrics.incrementNetworkRequests();
                    String responseUrl = response.url();
                    Map<String, String> headers = response.headers();

                    log.debug("Response from: {} with {} headers", responseUrl, headers.size());

                    // Check ALL header variations for cookies
                    for (Map.Entry<String, String> header : headers.entrySet()) {
                        String headerName = header.getKey().toLowerCase();
                        String headerValue = header.getValue();

                        if (headerName.contains("cookie") || headerName.equals("set-cookie")) {
                            log.info("COOKIE HEADER FOUND: {} = {}", headerName, headerValue);

                            String siteRoot = UrlAndCookieUtil.extractRootDomain(url);
                            String responseRoot = UrlAndCookieUtil.extractRootDomain(responseUrl);

                            Source source = siteRoot.equalsIgnoreCase(responseRoot)
                                    ? Source.FIRST_PARTY
                                    : Source.THIRD_PARTY;

                            // GENERIC THIRD-PARTY DETECTION
                            if (source == Source.THIRD_PARTY) {
                                log.info("THIRD-PARTY COOKIE RESPONSE: {} from {}", headerName, responseUrl);

                                // Extra wait for third-party responses to settle
                                try {
                                    Thread.sleep(500);
                                } catch (InterruptedException e) {
                                    Thread.currentThread().interrupt();
                                }
                            }

                            List<CookieDto> headerCookies = parseAllCookieHeaders(headerValue, responseUrl, responseRoot, source);

                            for (CookieDto cookie : headerCookies) {
                                // Set subdomain name based on response URL
                                String subdomainName = determineSubdomainNameFromUrl(responseUrl, url, subdomains);
                                cookie.setSubdomainName(subdomainName);

                                String cookieKey = generateCookieKey(cookie.getName(), cookie.getDomain());
                                if (!discoveredCookies.containsKey(cookieKey)) {
                                    discoveredCookies.put(cookieKey, cookie);
                                    categorizeSingleCookieSimple(cookie);
                                    saveIncrementalCookieWithFlush(transactionId, cookie);
                                    scanMetrics.incrementCookiesFound(source.name());
                                    metrics.recordCookieDiscovered(source.name());
                                    log.info("ADDED HTTP COOKIE: {} from {} (Source: {}, Subdomain: {})",
                                            cookie.getName(), responseUrl, source, subdomainName);
                                }
                            }
                        }
                    }
                } catch (Exception e) {
                    log.warn("Error processing response cookies: {}", e.getMessage());
                }
            });

            context.onRequest(request -> {
                processedUrls.add(request.url());
            });

            Page page = context.newPage();

            // === YOUR ORIGINAL 8 PHASES FOR MAIN URL - UNCHANGED ===

            // === PHASE 1: INITIAL LOAD WITH EXTENDED WAIT ===
            scanMetrics.setScanPhase("LOADING_PAGE");
            log.info("=== PHASE 1: Loading main page with extended wait ===");
            Response response = page.navigate(url, new Page.NavigateOptions().setWaitUntil(WaitUntilState.NETWORKIDLE));

            if (response == null || !response.ok()) {
                throw new ScanExecutionException("Failed to load page. Status: " +
                        (response != null ? response.status() : "No response"), null);
            }

            // Extended wait for all resources to load
            page.waitForTimeout(8000);

            // SPECIAL WAIT FOR EMBEDDED CONTENT
            if ((Boolean) page.evaluate("document.querySelectorAll('iframe, embed, object').length > 0")) {
                log.info("Embedded content detected - extending wait time");
                page.waitForTimeout(5000);
            }

            captureBrowserCookiesEnhanced(context, url, discoveredCookies, transactionId, scanMetrics);

            // === PHASE 2: FORCE EXTERNAL RESOURCE LOADING ===
            scanMetrics.setScanPhase("LOADING_EXTERNAL_RESOURCES");
            log.info("=== PHASE 2: Force loading external tracking resources ===");

            page.evaluate("""
                // Force load common tracking services
                (function() {
                    // Create tracking pixels manually
                    let trackingUrls = [
                        'https://www.google-analytics.com/collect?v=1&t=pageview&tid=UA-123456-1&cid=555',
                        'https://analytics.google.com/analytics/web/',
                        'https://googleads.g.doubleclick.net/pagead/viewthroughconversion/123456/',
                        'https://www.facebook.com/tr?id=123456&ev=PageView&noscript=1'
                    ];
                    
                    trackingUrls.forEach(url => {
                        let img = document.createElement('img');
                        img.src = url;
                        img.style.display = 'none';
                        img.style.width = '1px';
                        img.style.height = '1px';
                        document.body.appendChild(img);
                    });
                    
                    console.log('External resources loaded');
                })();
            """);

            page.waitForTimeout(5000);
            captureBrowserCookiesEnhanced(context, url, discoveredCookies, transactionId, scanMetrics);

            // === PHASE 3: AGGRESSIVE CONSENT HANDLING ===
            scanMetrics.setScanPhase("HANDLING_CONSENT");
            log.info("=== PHASE 3: Aggressive consent banner handling ===");

            boolean consentHandled = CookieDetectionUtil.handleConsentBanners(page, 15000);

            if (!consentHandled) {
                log.info("Trying manual consent detection...");
                try {
                    Boolean foundButton = (Boolean) page.evaluate("""
                        (function() {
                            let found = false;
                            let selectors = [
                                'button', 'a', 'div[role="button"]', 'span[role="button"]',
                                '[onclick]', '[data-testid]', '[data-cy]'
                            ];
                            
                            for (let selector of selectors) {
                                let elements = document.querySelectorAll(selector);
                                for (let elem of elements) {
                                    let text = (elem.textContent || elem.innerText || '').toLowerCase();
                                    let attrs = elem.outerHTML.toLowerCase();
                                    
                                    if (text.includes('accept') || text.includes('agree') || 
                                        text.includes('allow') || text.includes('consent') ||
                                        text.includes('continue') || text.includes('ok') ||
                                        attrs.includes('accept') || attrs.includes('consent')) {
                                        
                                        try {
                                            elem.click();
                                            console.log('Clicked consent element:', text || attrs);
                                            found = true;
                                            break;
                                        } catch(e) {
                                            continue;
                                        }
                                    }
                                }
                                if (found) break;
                            }
                            return found;
                        })();
                    """);

                    if (foundButton) {
                        consentHandled = true;
                        log.info("Manual consent handling successful!");
                    }
                } catch (Exception e) {
                    log.debug("Manual consent detection failed: {}", e.getMessage());
                }
            }

            if (consentHandled) {
                page.waitForTimeout(5000);
                captureBrowserCookiesEnhanced(context, url, discoveredCookies, transactionId, scanMetrics);
            }

            // === PHASE 4: INTENSIVE USER SIMULATION ===
            scanMetrics.setScanPhase("USER_INTERACTIONS");
            log.info("=== PHASE 4: Intensive user interaction simulation ===");

            // Multi-stage scrolling with analytics triggers
            for (int i = 0; i <= 10; i++) {
                double scrollPercent = i * 0.1;
                page.evaluate(String.format("window.scrollTo(0, document.body.scrollHeight * %f)", scrollPercent));

                // Trigger scroll events
                page.evaluate("""
                    window.dispatchEvent(new Event('scroll'));
                    if (typeof gtag === 'function') {
                        gtag('event', 'scroll', {
                            percent_scrolled: Math.round(window.pageYOffset / document.body.scrollHeight * 100)
                        });
                    }
                """);

                page.waitForTimeout(500);
            }

            // Mouse movements and clicks
            page.mouse().move(200, 200);
            page.waitForTimeout(300);
            page.mouse().move(800, 400);
            page.waitForTimeout(300);
            page.mouse().move(400, 600);

            page.waitForTimeout(3000);
            captureBrowserCookiesEnhanced(context, url, discoveredCookies, transactionId, scanMetrics);

            // === PHASE 5: ANALYTICS EVENT BOMBING ===
            scanMetrics.setScanPhase("TRIGGERING_ANALYTICS");
            log.info("=== PHASE 5: Analytics event bombing ===");

            page.evaluate("""
                // Fire all possible analytics events
                let events = ['pageview', 'click', 'scroll', 'engagement', 'conversion', 'purchase'];
                
                events.forEach(eventName => {
                    // Google Analytics
                    if (typeof gtag === 'function') {
                        gtag('event', eventName, {
                            event_category: 'engagement',
                            event_label: 'cookie_scan',
                            value: 1
                        });
                    }
                    
                    if (typeof ga === 'function') {
                        ga('send', 'event', 'engagement', eventName, 'cookie_scan', 1);
                    }
                    
                    // Facebook Pixel
                    if (typeof fbq === 'function') {
                        fbq('track', 'CustomEvent', {eventName: eventName});
                    }
                });
                
                // Dispatch DOM events
                ['load', 'DOMContentLoaded', 'resize', 'focus', 'blur', 'beforeunload'].forEach(evt => {
                    window.dispatchEvent(new Event(evt));
                });
                
                // Force cookie creation
                document.cookie = 'test_cookie=1; path=/';
                document.cookie = 'analytics_test=engaged; path=/';
                
                console.log('Analytics bombing completed');
            """);

            page.waitForTimeout(5000);
            captureBrowserCookiesEnhanced(context, url, discoveredCookies, transactionId, scanMetrics);

            // === PHASE 6: SUBDOMAIN AND CROSS-DOMAIN REQUESTS ===
            scanMetrics.setScanPhase("CROSS_DOMAIN_REQUESTS");
            log.info("=== PHASE 6: Cross-domain and subdomain requests ===");

            // Generic subdomain detection for any domain
            String rootDomain = UrlAndCookieUtil.extractRootDomain(url);
            String[] commonSubdomains = {"www", "api", "app", "mobile", "m", "accounts", "secure", "login"};

            for (String subdomain : commonSubdomains) {
                try {
                    String subdomainUrl = url.startsWith("https") ? "https://" : "http://";
                    subdomainUrl += subdomain + "." + rootDomain;

                    log.info("Trying subdomain: {}", subdomainUrl);
                    page.navigate(subdomainUrl, new Page.NavigateOptions().setTimeout(8000));
                    page.waitForTimeout(2000);
                    captureBrowserCookiesEnhanced(context, url, discoveredCookies, transactionId, scanMetrics);
                    break; // Only try first successful one
                } catch (Exception e) {
                    log.debug("Failed to visit subdomain: {}", e.getMessage());
                }
            }

            // === PHASE 7: HYPERLINK DISCOVERY AND SCANNING ===
            discoverAndScanHyperlinks(page, url, transactionId, discoveredCookies, scanMetrics);

            // === PHASE 8: IFRAME AND EMBED DETECTION ===
            handleIframesAndEmbeds(page, url, transactionId, discoveredCookies, scanMetrics);

            // === NEW PHASE 9: SCAN PROVIDED SUBDOMAINS ===
            if (subdomains != null && !subdomains.isEmpty()) {
                scanMetrics.setScanPhase("SCANNING_SUBDOMAINS");
                log.info("=== PHASE 9: Scanning {} provided subdomains ===", subdomains.size());

                for (int i = 0; i < subdomains.size(); i++) {
                    String subdomain = subdomains.get(i);
                    String subdomainName = SubdomainValidationUtil.extractSubdomainName(subdomain, rootDomain);

                    log.info("=== SUBDOMAIN {}/{}: {} (Name: {}) ===", i+1, subdomains.size(), subdomain, subdomainName);

                    try {
                        // Navigate to subdomain in SAME browser session
                        Response subdomainResponse = page.navigate(subdomain, new Page.NavigateOptions()
                                .setWaitUntil(WaitUntilState.NETWORKIDLE)
                                .setTimeout(30000));

                        if (subdomainResponse != null && subdomainResponse.ok()) {
                            page.waitForTimeout(8000);

                            // Capture initial cookies
                            captureBrowserCookiesWithSubdomainName(context, subdomain, discoveredCookies,
                                    transactionId, scanMetrics, subdomainName);

                            // Basic consent and interaction for subdomain
                            try {
                                boolean subdomainConsentHandled = CookieDetectionUtil.handleConsentBanners(page, 10000);
                                if (subdomainConsentHandled) {
                                    page.waitForTimeout(3000);
                                    captureBrowserCookiesWithSubdomainName(context, subdomain, discoveredCookies,
                                            transactionId, scanMetrics, subdomainName);
                                }

                                // Quick scroll and interaction
                                page.evaluate("window.scrollTo(0, document.body.scrollHeight / 2)");
                                page.waitForTimeout(2000);
                                page.evaluate("window.scrollTo(0, 0)");
                                page.waitForTimeout(1000);

                                // Trigger some analytics events
                                page.evaluate("""
                                    if (typeof gtag === 'function') {
                                        gtag('event', 'page_view');
                                    }
                                    window.dispatchEvent(new Event('scroll'));
                                """);
                                page.waitForTimeout(2000);

                                // Final capture for this subdomain
                                captureBrowserCookiesWithSubdomainName(context, subdomain, discoveredCookies,
                                        transactionId, scanMetrics, subdomainName);

                            } catch (Exception e) {
                                log.debug("Subdomain interaction failed for {}: {}", subdomain, e.getMessage());
                            }

                            log.info("Completed scanning subdomain: {} (Total cookies so far: {})",
                                    subdomain, discoveredCookies.size());
                        } else {
                            log.warn("Failed to load subdomain: {} (Status: {})", subdomain,
                                    subdomainResponse != null ? subdomainResponse.status() : "No response");
                        }
                    } catch (Exception e) {
                        log.warn("Error scanning subdomain {}: {}", subdomain, e.getMessage());
                    }
                }
            }

            // === FINAL CAPTURE ===
            scanMetrics.setScanPhase("FINAL_CAPTURE");
            page.waitForTimeout(8000);
            captureBrowserCookiesEnhanced(context, url, discoveredCookies, transactionId, scanMetrics);

            log.info("MAXIMUM DETECTION scan completed. Total unique cookies: {}, Network requests: {}, Subdomains scanned: {}",
                    discoveredCookies.size(), processedUrls.size(), subdomains != null ? subdomains.size() : 0);

        } catch (PlaywrightException e) {
            throw new ScanExecutionException("Playwright error during scan: " + e.getMessage(), e);
        } catch (Exception e) {
            throw new ScanExecutionException("Unexpected error during scan: " + e.getMessage(), e);
        } finally {
            cleanupResources(context, browser, playwright);
        }
    }

    // Backward compatibility overload
    private void performMaximumCookieDetection(String url, String transactionId,
                                               ScanPerformanceTracker.ScanMetrics scanMetrics)
            throws ScanExecutionException {
        performMaximumCookieDetection(url, transactionId, scanMetrics, null);
    }

    // NEW: Helper method to determine subdomain name from URL
    private String determineSubdomainNameFromUrl(String currentUrl, String mainUrl, List<String> subdomains) {
        try {
            String rootDomain = UrlAndCookieUtil.extractRootDomain(mainUrl);

            // If it's the main URL
            if (isSameUrl(currentUrl, mainUrl)) {
                return SubdomainValidationUtil.extractSubdomainName(mainUrl, rootDomain);
            }

            // If it's one of the provided subdomains
            if (subdomains != null) {
                for (String subdomain : subdomains) {
                    if (isSameUrl(currentUrl, subdomain)) {
                        return SubdomainValidationUtil.extractSubdomainName(subdomain, rootDomain);
                    }
                }
            }

            // Fallback - extract from current URL
            return SubdomainValidationUtil.extractSubdomainName(currentUrl, rootDomain);

        } catch (Exception e) {
            log.debug("Error determining subdomain name for {}: {}", currentUrl, e.getMessage());
            return "unknown";
        }
    }

    // NEW: Helper to check if URLs are from same domain/subdomain
    private boolean isSameUrl(String url1, String url2) {
        try {
            String host1 = new java.net.URI(url1.startsWith("http") ? url1 : "https://" + url1).getHost();
            String host2 = new java.net.URI(url2.startsWith("http") ? url2 : "https://" + url2).getHost();
            return host1.equalsIgnoreCase(host2);
        } catch (Exception e) {
            return false;
        }
    }

    // NEW: Capture cookies with specific subdomain name
    private void captureBrowserCookiesWithSubdomainName(BrowserContext context, String scanUrl,
                                                        Map<String, CookieDto> discoveredCookies,
                                                        String transactionId,
                                                        ScanPerformanceTracker.ScanMetrics scanMetrics,
                                                        String subdomainName) {
        try {
            String siteRoot = UrlAndCookieUtil.extractRootDomain(scanUrl);
            List<Cookie> browserCookies = context.cookies();

            log.info("=== CAPTURING {} browser cookies for subdomain: {} ===", browserCookies.size(), subdomainName);

            for (Cookie playwrightCookie : browserCookies) {
                try {
                    CookieDto cookieDto = mapPlaywrightCookie(playwrightCookie, scanUrl, siteRoot);
                    cookieDto.setSubdomainName(subdomainName);

                    String cookieKey = generateCookieKey(cookieDto.getName(), cookieDto.getDomain());

                    if (!discoveredCookies.containsKey(cookieKey)) {
                        discoveredCookies.put(cookieKey, cookieDto);
                        categorizeSingleCookieSimple(cookieDto);
                        saveIncrementalCookieWithFlush(transactionId, cookieDto);
                        scanMetrics.incrementCookiesFound(cookieDto.getSource().name());
                        metrics.recordCookieDiscovered(cookieDto.getSource().name());
                        log.info("ADDED SUBDOMAIN BROWSER COOKIE: {} from domain {} (Source: {}, Subdomain: {})",
                                cookieDto.getName(), cookieDto.getDomain(), cookieDto.getSource(), subdomainName);
                    }
                } catch (Exception e) {
                    log.warn("Error processing browser cookie {}: {}", playwrightCookie.name, e.getMessage());
                }
            }
        } catch (Exception e) {
            log.warn("Error capturing browser cookies for subdomain {}: {}", subdomainName, e.getMessage());
        }
    }

    // === ALL YOUR ORIGINAL METHODS - UNCHANGED ===

    private void captureBrowserCookiesEnhanced(BrowserContext context, String scanUrl,
                                               Map<String, CookieDto> discoveredCookies, String transactionId,
                                               ScanPerformanceTracker.ScanMetrics scanMetrics) {
        try {
            String siteRoot = UrlAndCookieUtil.extractRootDomain(scanUrl);
            List<Cookie> browserCookies = context.cookies();

            log.info("=== CAPTURING {} browser cookies ===", browserCookies.size());

            for (Cookie playwrightCookie : browserCookies) {
                try {
                    CookieDto cookieDto = mapPlaywrightCookie(playwrightCookie, scanUrl, siteRoot);
                    String cookieKey = generateCookieKey(cookieDto.getName(), cookieDto.getDomain());

                    if (!discoveredCookies.containsKey(cookieKey)) {
                        discoveredCookies.put(cookieKey, cookieDto);
                        categorizeSingleCookieSimple(cookieDto);
                        saveIncrementalCookieWithFlush(transactionId, cookieDto);
                        scanMetrics.incrementCookiesFound(cookieDto.getSource().name());
                        metrics.recordCookieDiscovered(cookieDto.getSource().name());
                        log.info("ADDED BROWSER COOKIE: {} from domain {} (Source: {})",
                                cookieDto.getName(), cookieDto.getDomain(), cookieDto.getSource());
                    }
                } catch (Exception e) {
                    log.warn("Error processing browser cookie {}: {}", playwrightCookie.name, e.getMessage());
                }
            }
        } catch (Exception e) {
            log.warn("Error capturing browser cookies: {}", e.getMessage());
        }
    }

    private List<CookieDto> parseAllCookieHeaders(String cookieHeader, String scanUrl,
                                                  String responseDomain, Source source) {
        List<CookieDto> cookies = new ArrayList<>();

        try {
            String[] cookieParts = cookieHeader.split(",(?=\\s*[^;]*=)");

            for (String cookiePart : cookieParts) {
                String trimmed = cookiePart.trim();
                if (!trimmed.isEmpty()) {
                    CookieDto cookie = parseSetCookieHeader(trimmed, scanUrl, responseDomain, source);
                    if (cookie != null) {
                        cookies.add(cookie);
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Error parsing cookie headers: {}", e.getMessage());
            CookieDto cookie = parseSetCookieHeader(cookieHeader, scanUrl, responseDomain, source);
            if (cookie != null) {
                cookies.add(cookie);
            }
        }

        return cookies;
    }

    private void categorizeSingleCookieSimple(CookieDto cookie) {
        try {
            String category = "Functional";
            String description = "Auto-categorized cookie";

            String cookieName = cookie.getName().toLowerCase();
            if (cookieName.contains("analytics") || cookieName.contains("_ga") || cookieName.contains("_gid")) {
                category = "Analytics";
                description = "Used for website analytics and performance tracking";
            } else if (cookieName.contains("ads") || cookieName.contains("doubleclick") || cookieName.contains("fbp")) {
                category = "Advertising";
                description = "Used for advertising and marketing purposes";
            } else if (cookieName.contains("session") || cookieName.contains("csrf") || cookieName.contains("auth")) {
                category = "Necessary";
                description = "Essential for website functionality";
            } else if (cookieName.contains("pref") || cookieName.contains("lang") || cookieName.contains("theme")) {
                category = "Preferences";
                description = "Stores user preferences and settings";
            }

            cookie.setCategory(category);
            cookie.setDescription(description);

        } catch (Exception e) {
            log.debug("Failed to categorize cookie '{}': {}", cookie.getName(), e.getMessage());
            cookie.setCategory("Unknown");
            cookie.setDescription("Categorization failed");
        }
    }

    private CookieDto mapPlaywrightCookie(Cookie playwrightCookie, String scanUrl, String siteRoot) {
        Instant expiry = playwrightCookie.expires != null ?
                Instant.ofEpochSecond(playwrightCookie.expires.longValue()) : null;

        String cookieDomain = playwrightCookie.domain;
        Source source = determineSourceType(cookieDomain, siteRoot);
        SameSite sameSite = parseSameSiteAttribute(playwrightCookie.sameSite);

        return new CookieDto(
                playwrightCookie.name,
                scanUrl,
                cookieDomain,
                playwrightCookie.path != null ? playwrightCookie.path : "/",
                expiry,
                playwrightCookie.secure,
                playwrightCookie.httpOnly,
                sameSite,
                source
        );
    }

    private void saveIncrementalCookieWithFlush(String transactionId, CookieDto cookieDto) {
        try {
            ScanResultEntity result = repository.findById(transactionId).orElse(null);
            if (result != null) {
                List<CookieEntity> currentCookies = result.getCookies();
                if (currentCookies == null) {
                    currentCookies = new ArrayList<>();
                    result.setCookies(currentCookies);
                }

                boolean exists = currentCookies.stream()
                        .anyMatch(c -> c.getName().equals(cookieDto.getName()) &&
                                Objects.equals(c.getDomain(), cookieDto.getDomain()));

                if (!exists) {
                    CookieEntity cookieEntity = ScanResultMapper.cookieDtoToEntity(cookieDto);
                    currentCookies.add(cookieEntity);
                    result.setCookies(currentCookies);
                    repository.save(result);
                }
            }
        } catch (Exception e) {
            log.warn("Failed to save cookie '{}': {}", cookieDto.getName(), e.getMessage());
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
            case "strict":
                return SameSite.STRICT;
            case "lax":
                return SameSite.LAX;
            case "none":
                return SameSite.NONE;
            default:
                return SameSite.LAX;
        }
    }

    private String generateCookieKey(String name, String domain) {
        return name + "@" + (domain != null ? domain.toLowerCase() : "");
    }

    private void handleIframesAndEmbeds(Page page, String url, String transactionId,
                                        Map<String, CookieDto> discoveredCookies,
                                        ScanPerformanceTracker.ScanMetrics scanMetrics) {
        try {
            scanMetrics.setScanPhase("IFRAME_DETECTION");
            log.info("=== PHASE 8: Detecting and processing iframes/embeds ===");

            List<IframeInfo> iframes = detectAllIframes(page);
            log.info("Found {} iframes/embeds on page", iframes.size());

            interactWithAllIframes(page, iframes);
            page.waitForTimeout(3000);
            captureBrowserCookiesEnhanced(page.context(), url, discoveredCookies, transactionId, scanMetrics);

            triggerGenericIframeEvents(page);
            page.waitForTimeout(2000);
            captureBrowserCookiesEnhanced(page.context(), url, discoveredCookies, transactionId, scanMetrics);

            loadCommonEmbedPatterns(page);
            page.waitForTimeout(3000);
            captureBrowserCookiesEnhanced(page.context(), url, discoveredCookies, transactionId, scanMetrics);

        } catch (Exception e) {
            log.warn("Error during iframe detection: {}", e.getMessage());
        }
    }

    private static class IframeInfo {
        String src;
        String domain;
        String type;

        IframeInfo(String src, String domain, String type) {
            this.src = src;
            this.domain = domain;
            this.type = type;
        }
    }

    private List<IframeInfo> detectAllIframes(Page page) {
        try {
            List<Map<String, String>> iframeData = (List<Map<String, String>>) page.evaluate("""
                Array.from(document.querySelectorAll('iframe, embed, object'))
                    .map(element => {
                        const src = element.src || element.data || '';
                        if (!src || !src.startsWith('http')) return null;
                        
                        let domain = '';
                        let type = 'embed';
                        
                        try {
                            domain = new URL(src).hostname;
                            
                            if (src.includes('video') || src.includes('player')) type = 'video';
                            else if (src.includes('social') || src.includes('widget')) type = 'social';
                            else if (src.includes('map')) type = 'maps';
                            else if (src.includes('captcha') || src.includes('security')) type = 'security';
                            else if (src.includes('ads') || src.includes('advertising')) type = 'advertising';
                            else if (src.includes('analytics') || src.includes('tracking')) type = 'analytics';
                            else type = 'embed';
                            
                        } catch(e) {
                            domain = src;
                        }
                        
                        return {src: src, domain: domain, type: type};
                    })
                    .filter(info => info !== null);
            """);

            return iframeData.stream()
                    .map(map -> new IframeInfo(map.get("src"), map.get("domain"), map.get("type")))
                    .collect(Collectors.toList());

        } catch (Exception e) {
            log.debug("Error detecting iframes: {}", e.getMessage());
            return new ArrayList<>();
        }
    }

    private void interactWithAllIframes(Page page, List<IframeInfo> iframes) {
        try {
            log.info("Interacting with {} detected iframes/embeds...", iframes.size());

            Map<String, List<IframeInfo>> iframesByType = iframes.stream()
                    .collect(Collectors.groupingBy(iframe -> iframe.type));

            log.info("Iframe types detected: {}", iframesByType.keySet());

            page.evaluate("""
                document.querySelectorAll('iframe, embed, object').forEach((element, index) => {
                    try {
                        element.scrollIntoView({behavior: 'smooth', block: 'center'});
                        
                        ['mouseover', 'mouseenter', 'focus', 'click'].forEach(eventType => {
                            element.dispatchEvent(new MouseEvent(eventType, {
                                bubbles: true,
                                cancelable: true,
                                view: window
                            }));
                        });
                        
                        if (element.focus) element.focus();
                        
                        console.log('Interacted with iframe/embed:', element.src || element.data);
                        
                    } catch(e) {
                        console.log('Error interacting with iframe:', e.message);
                    }
                });
            """);

            page.waitForTimeout(1000);

        } catch (Exception e) {
            log.debug("Error interacting with iframes: {}", e.getMessage());
        }
    }

    private void triggerGenericIframeEvents(Page page) {
        try {
            page.evaluate("""
                ['load', 'DOMContentLoaded', 'resize', 'focus', 'blur'].forEach(eventType => {
                    document.querySelectorAll('iframe').forEach(iframe => {
                        try {
                            iframe.dispatchEvent(new Event(eventType, {bubbles: true}));
                        } catch(e) {}
                    });
                });
                
                window.dispatchEvent(new Event('message'));
                window.dispatchEvent(new Event('resize'));
                
                document.querySelectorAll('iframe').forEach(iframe => {
                    try {
                        iframe.contentWindow.postMessage('ping', '*');
                    } catch(e) {}
                });
            """);
        } catch (Exception e) {
            log.debug("Error triggering iframe events: {}", e.getMessage());
        }
    }

    private void loadCommonEmbedPatterns(Page page) {
        try {
            page.evaluate("""
                const existingScripts = Array.from(document.querySelectorAll('script[src]'))
                    .map(s => s.src)
                    .filter(src => src.includes('//') && !src.includes(window.location.hostname));
                
                console.log('Found external scripts:', existingScripts.length);
                
                window.dispatchEvent(new Event('message'));
                window.dispatchEvent(new Event('resize'));
                window.dispatchEvent(new Event('beforeunload'));
                
                const img = document.createElement('img');
                img.style.display = 'none';
                img.style.width = '1px';
                img.style.height = '1px';
                img.onload = () => console.log('Generic tracking pattern triggered');
                
                window.postMessage({type: 'resize'}, '*');
                window.postMessage({type: 'scroll'}, '*');
            """);
        } catch (Exception e) {
            log.debug("Error in generic embed pattern handling: {}", e.getMessage());
        }
    }

    private void discoverAndScanHyperlinks(Page page, String originalUrl, String transactionId,
                                           Map<String, CookieDto> discoveredCookies,
                                           ScanPerformanceTracker.ScanMetrics scanMetrics) {
        try {
            scanMetrics.setScanPhase("DISCOVERING_HYPERLINKS");
            log.info("=== PHASE 7: Discovering and scanning hyperlinks ===");

            String rootDomain = UrlAndCookieUtil.extractRootDomain(originalUrl);

            List<String> priorityLinks = discoverPriorityLinks(page, rootDomain);
            log.info("Found {} priority links", priorityLinks.size());

            List<String> navigationLinks = discoverNavigationLinks(page, rootDomain);
            log.info("Found {} navigation links", navigationLinks.size());

            List<String> subdomainLinks = discoverSubdomainLinks(page, rootDomain);
            log.info("Found {} subdomain links", subdomainLinks.size());

            visitLinksWithCookieCapture(page, priorityLinks, 5, "priority",
                    discoveredCookies, transactionId, scanMetrics);

            visitLinksWithCookieCapture(page, subdomainLinks, 3, "subdomain",
                    discoveredCookies, transactionId, scanMetrics);

            visitLinksWithCookieCapture(page, navigationLinks, 2, "navigation",
                    discoveredCookies, transactionId, scanMetrics);

        } catch (Exception e) {
            log.warn("Error during hyperlink discovery: {}", e.getMessage());
        }
    }

    private List<String> discoverPriorityLinks(Page page, String rootDomain) {
        Set<String> priorityLinks = new LinkedHashSet<>();

        try {
            String[] priorityPatterns = {
                    "login", "signin", "sign-in", "log-in",
                    "account", "accounts", "myaccount", "my-account",
                    "profile", "user", "dashboard", "settings",
                    "checkout", "cart", "order", "payment", "billing",
                    "register", "signup", "sign-up", "join",
                    "preferences", "privacy", "security"
            };

            for (String pattern : priorityPatterns) {
                try {
                    List<String> links = (List<String>) page.evaluate(String.format("""
                        Array.from(document.querySelectorAll('a[href]'))
                            .filter(a => {
                                const href = a.href.toLowerCase();
                                const text = (a.textContent || '').toLowerCase();
                                return href.includes('%s') || text.includes('%s');
                            })
                            .map(a => a.href)
                            .filter(href => href.startsWith('http'))
                            .slice(0, 3);
                    """, pattern, pattern));

                    for (String link : links) {
                        String linkDomain = UrlAndCookieUtil.extractRootDomain(link);
                        if (rootDomain.equals(linkDomain)) {
                            priorityLinks.add(link);
                            if (priorityLinks.size() >= 10) break;
                        }
                    }

                } catch (Exception e) {
                    log.debug("Error finding links for pattern '{}': {}", pattern, e.getMessage());
                }

                if (priorityLinks.size() >= 10) break;
            }

        } catch (Exception e) {
            log.debug("Error discovering priority links: {}", e.getMessage());
        }

        return new ArrayList<>(priorityLinks);
    }

    private List<String> discoverNavigationLinks(Page page, String rootDomain) {
        Set<String> navLinks = new LinkedHashSet<>();

        try {
            List<String> links = (List<String>) page.evaluate("""
                Array.from(document.querySelectorAll(`
                    nav a, .nav a, .navigation a, .navbar a,
                    .menu a, .main-menu a, header a,
                    .top-menu a, .primary-nav a, .site-nav a
                `))
                    .map(a => a.href)
                    .filter(href => href && href.startsWith('http'))
                    .slice(0, 15);
            """);

            for (String link : links) {
                try {
                    String linkDomain = UrlAndCookieUtil.extractRootDomain(link);
                    if (rootDomain.equals(linkDomain)) {
                        navLinks.add(link);
                        if (navLinks.size() >= 8) break;
                    }
                } catch (Exception e) {
                    // Skip invalid URLs
                }
            }

        } catch (Exception e) {
            log.debug("Error discovering navigation links: {}", e.getMessage());
        }

        return new ArrayList<>(navLinks);
    }

    private List<String> discoverSubdomainLinks(Page page, String rootDomain) {
        Set<String> subdomainLinks = new LinkedHashSet<>();

        try {
            List<String> allLinks = (List<String>) page.evaluate("""
                Array.from(document.querySelectorAll('a[href], script[src], link[href]'))
                    .map(el => el.href || el.src)
                    .filter(url => url && url.startsWith('http'))
                    .slice(0, 50);
            """);

            for (String link : allLinks) {
                try {
                    String linkDomain = UrlAndCookieUtil.extractRootDomain(link);
                    if (rootDomain.equals(linkDomain)) {
                        String host = new URL(link).getHost().toLowerCase();
                        if (!host.equals(rootDomain) && host.endsWith("." + rootDomain)) {
                            subdomainLinks.add(link);
                            if (subdomainLinks.size() >= 6) break;
                        }
                    }
                } catch (Exception e) {
                    // Skip invalid URLs
                }
            }

        } catch (Exception e) {
            log.debug("Error discovering subdomain links: {}", e.getMessage());
        }

        return new ArrayList<>(subdomainLinks);
    }

    private void visitLinksWithCookieCapture(Page page, List<String> links, int maxLinks, String linkType,
                                             Map<String, CookieDto> discoveredCookies, String transactionId,
                                             ScanPerformanceTracker.ScanMetrics scanMetrics) {

        int visited = 0;
        String originalUrl = page.url();

        for (String link : links) {
            if (visited >= maxLinks) break;

            try {
                log.info("Visiting {} link: {}", linkType, link);

                Response response = page.navigate(link, new Page.NavigateOptions()
                        .setTimeout(15000)
                        .setWaitUntil(WaitUntilState.DOMCONTENTLOADED));

                if (response != null && response.ok()) {
                    page.waitForTimeout(3000);

                    int cookiesBefore = discoveredCookies.size();
                    captureBrowserCookiesEnhanced(page.context(), link, discoveredCookies, transactionId, scanMetrics);

                    performLinkPageInteractions(page);

                    page.waitForTimeout(2000);
                    captureBrowserCookiesEnhanced(page.context(), link, discoveredCookies, transactionId, scanMetrics);

                    int cookiesAfter = discoveredCookies.size();
                    int newCookies = cookiesAfter - cookiesBefore;

                    visited++;
                    log.info("Successfully processed {} link: {} (+{} cookies, total: {})",
                            linkType, link, newCookies, cookiesAfter);

                    scanMetrics.incrementNetworkRequests();

                } else {
                    log.debug("Failed to load {} link: {} (status: {})",
                            linkType, link, response != null ? response.status() : "unknown");
                }

            } catch (Exception e) {
                log.debug("Error visiting {} link {}: {}", linkType, link, e.getMessage());
            }
        }

        if (visited > 0) {
            try {
                page.navigate(originalUrl, new Page.NavigateOptions().setTimeout(10000));
                page.waitForTimeout(2000);
            } catch (Exception e) {
                log.debug("Error returning to original page: {}", e.getMessage());
            }
        }
    }

    private void performLinkPageInteractions(Page page) {
        try {
            page.evaluate("window.scrollTo(0, document.body.scrollHeight / 2)");
            page.waitForTimeout(1000);

            try {
                if (page.locator("input[type='email'], input[type='text'], input[name*='email']").count() > 0) {
                    page.locator("input[type='email'], input[type='text'], input[name*='email']").first().click();
                    page.waitForTimeout(500);
                }
            } catch (Exception e) {
                // Ignore form interaction errors
            }

            page.evaluate("""
                window.dispatchEvent(new Event('scroll'));
                window.dispatchEvent(new Event('focus'));
                
                if (typeof gtag === 'function') {
                    gtag('event', 'page_view', {
                        page_title: document.title,
                        page_location: window.location.href
                    });
                }
            """);

        } catch (Exception e) {
            log.debug("Error in link page interactions: {}", e.getMessage());
        }
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