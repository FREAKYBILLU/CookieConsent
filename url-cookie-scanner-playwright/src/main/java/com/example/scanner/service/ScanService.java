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
import com.microsoft.playwright.*;
import com.microsoft.playwright.options.Cookie;
import com.microsoft.playwright.options.WaitUntilState;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

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
            result.setCookies(new ArrayList<>());

            try {
                repository.save(result);
                metrics.recordScanStarted();
            } catch (Exception e) {
                log.error("Failed to save scan result to database", e);
                throw new ScanExecutionException("Failed to initialize scan", e);
            }

            log.info("Created new scan with transactionId={} for URL={}", transactionId, normalizedUrl);

            self.runScanAsync(transactionId, normalizedUrl);

            return transactionId;

        } catch (UrlValidationException e) {
            throw e;
        } catch (Exception e) {
            log.error("Unexpected error during scan initialization", e);
            throw new ScanExecutionException("Failed to start scan", e);
        }
    }

    @Async
    public void runScanAsync(String transactionId, String url) {
        log.info("Starting MAXIMUM COOKIE DETECTION scan for transactionId={} URL={}", transactionId, url);

        ScanPerformanceTracker.ScanMetrics scanMetrics = new ScanPerformanceTracker.ScanMetrics();
        long scanStartTime = System.currentTimeMillis();
        ScanResultEntity result = null;

        try {
            result = repository.findById(transactionId)
                    .orElseThrow(() -> new IllegalStateException("Scan result not found for transactionId: " + transactionId));

            result.setStatus(ScanStatus.RUNNING.name());
            repository.save(result);

            scanMetrics.setScanPhase("RUNNING");
            performMaximumCookieDetection(url, transactionId, scanMetrics);

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

    private void performMaximumCookieDetection(String url, String transactionId,
                                               ScanPerformanceTracker.ScanMetrics scanMetrics)
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
                    .setTimeout(90000)  // Increased timeout
                    .setSlowMo(200);    // Slower for better detection

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

                            List<CookieDto> headerCookies = parseAllCookieHeaders(headerValue, responseUrl, responseRoot, source);

                            for (CookieDto cookie : headerCookies) {
                                String cookieKey = generateCookieKey(cookie.getName(), cookie.getDomain());
                                if (!discoveredCookies.containsKey(cookieKey)) {
                                    discoveredCookies.put(cookieKey, cookie);
                                    categorizeSingleCookieSimple(cookie);
                                    saveIncrementalCookieWithFlush(transactionId, cookie);
                                    scanMetrics.incrementCookiesFound(source.name());
                                    metrics.recordCookieDiscovered(source.name());
                                    log.info("ADDED HTTP COOKIE: {} from {} (Source: {})", cookie.getName(), responseUrl, source);
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
            captureBrowserCookiesEnhanced(context, url, discoveredCookies, transactionId, scanMetrics);

            // === PHASE 2: FORCE EXTERNAL RESOURCE LOADING ===
            scanMetrics.setScanPhase("LOADING_EXTERNAL_RESOURCES");
            log.info("=== PHASE 2: Force loading external tracking resources ===");

            page.evaluate("""
                // Force load Google Analytics
                (function() {
                    if (!window.gtag && !window.ga) {
                        console.log('Loading Google Analytics...');
                        let script1 = document.createElement('script');
                        script1.async = true;
                        script1.src = 'https://www.googletagmanager.com/gtag/js?id=GA_MEASUREMENT_ID';
                        document.head.appendChild(script1);
                        
                        let script2 = document.createElement('script');
                        script2.innerHTML = `
                            window.dataLayer = window.dataLayer || [];
                            function gtag(){dataLayer.push(arguments);}
                            gtag('js', new Date());
                            gtag('config', 'GA_MEASUREMENT_ID');
                        `;
                        document.head.appendChild(script2);
                    }
                    
                    // Load Facebook Pixel
                    if (!window.fbq) {
                        console.log('Loading Facebook Pixel...');
                        let fbScript = document.createElement('script');
                        fbScript.innerHTML = `
                            !function(f,b,e,v,n,t,s)
                            {if(f.fbq)return;n=f.fbq=function(){n.callMethod?
                            n.callMethod.apply(n,arguments):n.queue.push(arguments)};
                            if(!f._fbq)f._fbq=n;n.push=n;n.loaded=!0;n.version='2.0';
                            n.queue=[];t=b.createElement(e);t.async=!0;
                            t.src=v;s=b.getElementsByTagName(e)[0];
                            s.parentNode.insertBefore(t,s)}(window,document,'script',
                            'https://connect.facebook.net/en_US/fbevents.js');
                            fbq('init', 'YOUR_PIXEL_ID');
                            fbq('track', 'PageView');
                        `;
                        document.head.appendChild(fbScript);
                    }
                    
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

            // Manual consent attempt
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

            // Visit related subdomains if it's Google
            if (url.contains("google.com")) {
                String[] googleSubdomains = {
                        "https://accounts.google.com",
                        "https://myaccount.google.com",
                        "https://ads.google.com",
                        "https://analytics.google.com"
                };

                for (String subdomain : googleSubdomains) {
                    try {
                        log.info("Visiting Google subdomain: {}", subdomain);
                        page.navigate(subdomain, new Page.NavigateOptions().setTimeout(10000));
                        page.waitForTimeout(3000);
                        captureBrowserCookiesEnhanced(context, url, discoveredCookies, transactionId, scanMetrics);
                    } catch (Exception e) {
                        log.debug("Failed to visit subdomain {}: {}", subdomain, e.getMessage());
                    }
                }
            }

            // === FINAL CAPTURE ===
            scanMetrics.setScanPhase("FINAL_CAPTURE");
            page.waitForTimeout(8000);
            captureBrowserCookiesEnhanced(context, url, discoveredCookies, transactionId, scanMetrics);

            log.info("MAXIMUM DETECTION scan completed. Total unique cookies: {}, Network requests: {}",
                    discoveredCookies.size(), processedUrls.size());

        } catch (PlaywrightException e) {
            throw new ScanExecutionException("Playwright error during scan: " + e.getMessage(), e);
        } catch (Exception e) {
            throw new ScanExecutionException("Unexpected error during scan: " + e.getMessage(), e);
        } finally {
            cleanupResources(context, browser, playwright);
        }
    }

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

    // === HELPER METHODS ===

    private List<CookieDto> parseAllCookieHeaders(String cookieHeader, String scanUrl,
                                                  String responseDomain, Source source) {
        List<CookieDto> cookies = new ArrayList<>();

        try {
            // Split by comma but be careful with comma in expires
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
            // Fallback to single cookie
            CookieDto cookie = parseSetCookieHeader(cookieHeader, scanUrl, responseDomain, source);
            if (cookie != null) {
                cookies.add(cookie);
            }
        }

        return cookies;
    }

    private void categorizeSingleCookieSimple(CookieDto cookie) {
        try {
            // Simple categorization without external API
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

    // Rest of helper methods remain the same...
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