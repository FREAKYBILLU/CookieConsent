package com.example.scanner.service;

import com.example.scanner.config.ScannerConfigurationProperties;
import com.example.scanner.config.StoragePatternsConfig;
import com.example.scanner.config.TrackingPatternsConfig;
import com.example.scanner.dto.CookieCategorizationResponse;
import com.example.scanner.dto.CookieDto;
import com.example.scanner.dto.ScanStatusResponse;
import com.example.scanner.entity.CookieEntity;
import com.example.scanner.entity.ScanResultEntity;
import com.example.scanner.enums.SameSite;
import com.example.scanner.enums.ScanStatus;
import com.example.scanner.enums.Source;
import com.example.scanner.exception.ScanExecutionException;
import com.example.scanner.exception.ScannerException;
import com.example.scanner.exception.TransactionNotFoundException;
import com.example.scanner.exception.UrlValidationException;
import com.example.scanner.mapper.ScanResultMapper;
import com.example.scanner.repository.ScanResultRepository;
import com.example.scanner.util.CookieDetectionUtil;
import com.example.scanner.util.UrlAndCookieUtil;
import com.example.scanner.util.UrlAndCookieUtil.ValidationResult;
import com.example.scanner.util.SubdomainValidationUtil;
import com.microsoft.playwright.*;
import com.microsoft.playwright.options.Cookie;
import com.microsoft.playwright.options.LoadState;
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
    private final TrackingPatternsConfig trackingPatterns;
    private final StoragePatternsConfig storagePatternsConfig;
    private final Map<String, CookieDto> allDiscoveredCookies = new ConcurrentHashMap<>();

    private final Map<String, CookieDto> cookiesAwaitingCategorization = new ConcurrentHashMap<>();

    // ====== Deep crawl configuration (tune these) ======
    private static final int DEEP_CRAWL_MAX_DEPTH = 2;
    private static final int DEEP_CRAWL_MAX_PAGES = 15;
    private static final int DEEP_CRAWL_MAX_PAGES_PER_HOST = 3;
    private static final int LINKS_PER_PAGE = 5;

    // small helper for crawl stack
    private static class UrlDepth {
        final String url;
        final int depth;
        UrlDepth(String url, int depth) { this.url = url; this.depth = depth; }
    }

    @Lazy
    @Autowired
    private ScanService self;

    public ScanService(ScanResultRepository repository,
                       CookieCategorizationService cookieCategorizationService,
                       ScannerConfigurationProperties config,
                       CookieScanMetrics metrics, TrackingPatternsConfig trackingPatterns, StoragePatternsConfig storagePatternsConfig){
        this.repository = repository;
        this.cookieCategorizationService = cookieCategorizationService;
        this.config = config;
        this.metrics = metrics;
        this.trackingPatterns = trackingPatterns;
        this.storagePatternsConfig = storagePatternsConfig;
        log.info("=== TRACKING PATTERNS LOADED ===");
        log.info("File extensions count: {}", trackingPatterns.getUrlPatterns().getFileExtensions().size());
        log.info("File extensions: {}", trackingPatterns.getUrlPatterns().getFileExtensions());
        log.info("Endpoints count: {}", trackingPatterns.getUrlPatterns().getEndpoints().size());
        log.info("ID patterns count: {}", trackingPatterns.getParameterPatterns().getIdPatterns().size());
        log.info("Short cryptic patterns: {}", trackingPatterns.getParameterPatterns().getShortCryptic());
    }

    // NEW: Main method with subdomain support
    public String startScan(String url, List<String> subdomains) throws UrlValidationException, ScanExecutionException {
        log.info("Received request to scan URL: {} with {} subdomains", url, subdomains != null ? subdomains.size() : 0);

        try {
            ValidationResult validationResult = UrlAndCookieUtil.validateUrlForScanning(url);
            if (!validationResult.isValid()) {
                throw new UrlValidationException(
                        "Invalid URL provided",
                        validationResult.getErrorMessage()
                );
            }

            String normalizedUrl = validationResult.getNormalizedUrl();
            log.info("URL validation passed. Normalized URL: {}", normalizedUrl);

            // Validate subdomains if provided
            List<String> validatedSubdomains = new ArrayList<>();
            if (subdomains != null && !subdomains.isEmpty()) {
                SubdomainValidationUtil.ValidationResult subdomainValidation =
                        SubdomainValidationUtil.validateSubdomains(normalizedUrl, subdomains);

                if (!subdomainValidation.isValid()) {
                    throw new UrlValidationException(
                            "Invalid subdomains provided",
                            "Subdomain validation failed: " + subdomainValidation.getErrorMessage()
                    );
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

            try {
                repository.save(result);
                metrics.recordScanStarted();
            } catch (Exception e) {
                log.error("Failed to save scan result to database", e);
                throw new ScanExecutionException("Failed to initialize scan: " + e.getMessage());
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
            throw new ScanExecutionException("Failed to start scan: " + e.getMessage());
        }
    }

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

            log.info("MAXIMUM DETECTION scan COMPLETED for transactionId={} in {}ms",
                    transactionId, totalDuration.toMillis());

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
                    .setTimeout(120000)
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
            context.setDefaultTimeout(30000);
            context.setDefaultNavigationTimeout(30000);

            // ENHANCED response monitoring - ALL HEADERS
            // ENHANCED response monitoring - MORE comprehensive header checking
            context.onResponse(response -> {
                try {
                    scanMetrics.incrementNetworkRequests();
                    String responseUrl = response.url();
                    Map<String, String> headers = response.headers();

                    log.debug("Response from: {} with {} headers", responseUrl, headers.size());

                    // Check ALL headers for cookie-related content
                    for (Map.Entry<String, String> header : headers.entrySet()) {
                        String headerName = header.getKey().toLowerCase();
                        String headerValue = header.getValue();

                        // COMPLIANCE-FOCUSED: Only process headers that matter for privacy compliance
                        if (headerName.equals("set-cookie") ||
                                headerName.equals("cookie") ||
                                headerName.equals("x-set-cookie") ||
                                (headerName.contains("auth") && headerValue.length() > 10) ||
                                (headerName.contains("session") && headerValue.length() > 10) ||
                                (headerName.contains("token") && headerValue.length() > 20)) {

                            log.info("COMPLIANCE-RELEVANT HEADER: {} = {}", headerName, headerValue.substring(0, Math.min(50, headerValue.length())));

                            String siteRoot = UrlAndCookieUtil.extractRootDomain(url);
                            String responseRoot = UrlAndCookieUtil.extractRootDomain(responseUrl);

                            // ONLY process if it's from the main domain (compliance focus)
                            if (siteRoot.equalsIgnoreCase(responseRoot)) {
                                Source source = Source.FIRST_PARTY;
                                List<CookieDto> headerCookies = parseAllCookieHeaders(headerValue, responseUrl, responseRoot, source);

                            for (CookieDto cookie : headerCookies) {
                                String subdomainName = determineSubdomainNameFromUrl(responseUrl, url, subdomains);
                                cookie.setSubdomainName(subdomainName);

                                String cookieKey = generateCookieKey(cookie.getName(), cookie.getDomain(), cookie.getSubdomainName());
                                if (!discoveredCookies.containsKey(cookieKey)) {
                                    discoveredCookies.put(cookieKey, cookie);
                                    categorizeWithExternalAPI(cookie);
                                    saveIncrementalCookieWithFlush(transactionId, cookie);
                                    scanMetrics.incrementCookiesFound(source.name());
                                    metrics.recordCookieDiscovered(source.name());
                                    log.info("ADDED HEADER COOKIE: {} from {} (Source: {}, Subdomain: {})",
                                            cookie.getName(), responseUrl, source, subdomainName);
                                }
                            }
                        }
                    }}

                    // Check for tracking responses and extract URL parameters
                    if (isTrackingResponse(responseUrl)) {
                        log.info("GENERIC TRACKING RESPONSE: {}", responseUrl);
                        extractTrackingDataFromUrl(responseUrl, url, discoveredCookies, transactionId, scanMetrics, subdomains);

                        // Add extra wait for tracking responses
                        if (response.status() == 200) {
                            try {
                                Thread.sleep(1000);
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
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
            Response response = null;
            try {
                // Try networkidle first (30 second timeout)
                response = page.navigate(url, new Page.NavigateOptions()
                        .setWaitUntil(WaitUntilState.NETWORKIDLE)
                        .setTimeout(30000));
            } catch (TimeoutError e) {
                log.warn("Networkidle timeout, trying with domcontentloaded for {}", url);
                try {
                    // Fallback to domcontentloaded (faster but less complete)
                    response = page.navigate(url, new Page.NavigateOptions()
                            .setWaitUntil(WaitUntilState.DOMCONTENTLOADED)
                            .setTimeout(15000));
                } catch (TimeoutError e2) {
                    log.warn("Domcontentloaded timeout, trying basic load for {}", url);
                    // Last resort - just wait for basic load
                    response = page.navigate(url, new Page.NavigateOptions()
                            .setWaitUntil(WaitUntilState.LOAD)
                            .setTimeout(10000));
                }
            }
            if (response == null || !response.ok()) {
                throw new ScanExecutionException("Failed to load page. Status: " + (response != null ? response.status() : "No response"));
            }

            page.waitForTimeout(1000);

            // AGGRESSIVE: Multiple cookie capture passes
            for (int pass = 1; pass <= 3; pass++) {
                log.info("Cookie capture pass {} of 3", pass);
                captureBrowserCookiesEnhanced(context, url, discoveredCookies, transactionId, scanMetrics);

                if (pass < 3) {
                    page.waitForTimeout(2000); // Wait between passes
                }
            }

            if ((Boolean) page.evaluate("document.querySelectorAll('iframe, embed, object').length > 0")) {
                log.info("Embedded content detected - extending wait time");
                page.waitForTimeout(3000);
            }

            captureBrowserCookiesEnhanced(context, url, discoveredCookies, transactionId, scanMetrics);
//            captureStorageItemsSelectively(page, url, discoveredCookies, transactionId, scanMetrics);

            // === PHASE 2: GENERIC EXTERNAL RESOURCE LOADING ===
            scanMetrics.setScanPhase("LOADING_EXTERNAL_RESOURCES");
            log.info("=== PHASE 2: Generic external resource detection and triggering ===");

            page.evaluate("""
    // ENHANCED: Generic pixel tracker and authentication detection
    (function() {
        console.log('Starting enhanced resource detection...');
        
        // 1. Find ALL external resources
        const allExternalResources = Array.from(document.querySelectorAll(`
            script[src], img[src], iframe[src], link[href], 
            embed[src], object[data], video[src], audio[src],
            [src*="//"], [href*="//"], [data*="//"]
        `)).filter(el => {
            const url = el.src || el.href || el.data || '';
            return url && typeof url === 'string' && url.includes('//') && !url.includes(window.location.hostname);
        });
        
        console.log('Found external resources:', allExternalResources.length);
        
        // 2. Extract unique external domains
        const externalDomains = [...new Set(
            allExternalResources.map(el => {
                try {
                    const url = el.src || el.href || el.data || '';
                    if (url && typeof url === 'string') {
                        return new URL(url).origin;
                    }
                    return null;
                } catch(e) { 
                    return null; 
                }
            }).filter(Boolean)
        )];
        
        console.log('Unique external domains:', externalDomains.length);
        
        // 3. GENERIC: Comprehensive pixel patterns
        const pixelPatterns = [
            '/c.gif', '/px.gif', '/pixel.gif', '/collect', '/beacon',
            '/track', '/analytics', '/log', '/hit', '/event',
            '/p.gif', '/b.gif', '/i.gif', '/1x1.gif', '/pixel.png',
            '/tr', '/impression', '/click', '/conversion'
        ];
        
        const trackingParams = [
            '?t=' + Date.now(),
            '?r=' + Math.random(),
            '?pid=' + Math.random().toString(36).substr(2, 9),
            '?uid=' + Math.random().toString(36).substr(2, 16),
            '?v=1&t=pageview',
            '?action=pageview',
            '?event=page_view',
            ''
        ];
        
        // 4. GENERIC: Common authentication endpoints
        const authEndpoints = [
            '/oauth/authorize', '/auth/login', '/sso', '/login',
            '/connect', '/token', '/me', '/userinfo'
        ];
        
        // Try pixel patterns on ALL external domains
        externalDomains.forEach(domain => {
            pixelPatterns.forEach(pattern => {
                trackingParams.forEach(params => {
                    try {
                        const img = document.createElement('img');
                        img.src = domain + pattern + params;
                        img.style.display = 'none';
                        img.style.width = '1px';
                        img.style.height = '1px';
                        img.onload = () => console.log('Pixel loaded:', domain + pattern);
                        img.onerror = () => {};
                        document.body.appendChild(img);
                    } catch(e) {}
                });
            });
            
            // Try authentication endpoints (for cookie setting)
            authEndpoints.forEach(endpoint => {
                try {
                    const img = document.createElement('img');
                    img.src = domain + endpoint + '?check=' + Date.now();
                    img.style.display = 'none';
                    img.style.width = '1px';
                    img.style.height = '1px';
                    img.onerror = () => {};
                    document.body.appendChild(img);
                } catch(e) {}
            });
        });
        
        // 5. Trigger existing resources more aggressively
        allExternalResources.forEach(resource => {
            try {
                ['load', 'loadstart', 'loadend', 'progress'].forEach(eventType => {
                    resource.dispatchEvent(new Event(eventType, {bubbles: true}));
                });
                
                if (resource.tagName === 'SCRIPT' && resource.src) {
                    const newScript = document.createElement('script');
                    newScript.src = resource.src + (resource.src.includes('?') ? '&' : '?') + 
                                   'cache_bust=' + Date.now();
                    document.head.appendChild(newScript);
                }
            } catch(e) {}
        });
        
        console.log('Enhanced resource detection completed');
    })();
""");

            // LONGER WAIT: Give more time for aggressive pixel loading
            page.waitForTimeout(3000); // Increased from 5000
            captureBrowserCookiesEnhanced(context, url, discoveredCookies, transactionId, scanMetrics);

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
                captureStorageItemsSelectively(page, url, discoveredCookies, transactionId, scanMetrics);
                log.info("Captured storage after consent handling");
            }

            // === NEW: GENERIC AUTHENTICATION FLOW DETECTION ===
            scanMetrics.setScanPhase("AUTHENTICATION_DETECTION");
            log.info("=== PHASE 3.5: Generic authentication flow detection ===");
            detectAndTriggerAuthenticationFlows(page, url, transactionId, discoveredCookies, scanMetrics);
            captureStorageItemsSelectively(page, url, discoveredCookies, transactionId, scanMetrics); // NEW
            log.info("Captured storage after authentication triggers");

            // === NEW: SOCIAL WIDGET DETECTION ===
            scanMetrics.setScanPhase("SOCIAL_WIDGETS");
            log.info("=== PHASE 3.6: Social widget detection ===");
            detectAndTriggerSocialWidgets(page, url, transactionId, discoveredCookies, scanMetrics);
            captureStorageItemsSelectively(page, url, discoveredCookies, transactionId, scanMetrics); // NEW
            log.info("Captured storage after social widget detection");

            // === PHASE 4: AGGRESSIVE USER SIMULATION ===
            scanMetrics.setScanPhase("USER_INTERACTIONS");
            log.info("=== PHASE 4: Aggressive user interaction simulation ===");

            // AGGRESSIVE: More comprehensive scrolling
            // AGGRESSIVE: More comprehensive scrolling
            for (int i = 0; i <= 8; i++) {
                double scrollPercent = i * 0.05;
                page.evaluate(String.format("window.scrollTo(0, document.body.scrollHeight * %f)", scrollPercent));

                page.evaluate("""
        window.dispatchEvent(new Event('scroll'));
        window.dispatchEvent(new Event('resize'));
        
        if (typeof gtag === 'function') {
            gtag('event', 'scroll', {
                percent_scrolled: Math.round(window.pageYOffset / document.body.scrollHeight * 100)
            });
        }
    """);

                page.waitForTimeout(300);

                // NEW: Capture storage every 2 scroll steps
                if (i % 2 == 0 && i > 0) {
                    captureStorageItemsSelectively(page, url, discoveredCookies, transactionId, scanMetrics);
                    log.debug("Captured storage at scroll step {}", i);
                }
            }

            // AGGRESSIVE: Click on more elements
            page.evaluate("""
            // Find and interact with MORE element types
            const interactiveSelectors = [
                'button', 'a', 'input', 'textarea', 'select',
                '[onclick]', '[role="button"]', '[tabindex]',
                '.btn', '.button', '.link', '.nav-item',
                'nav *', 'header *', 'footer *',
                '[data-*]', '[id*="button"]', '[class*="click"]'
            ];
            
            interactiveSelectors.forEach(selector => {
                try {
                    const elements = document.querySelectorAll(selector);
                    Array.from(elements).slice(0, 5).forEach(el => {
                        try {
                            // Multiple interaction types
                            ['mouseover', 'mouseenter', 'focus', 'click'].forEach(eventType => {
                                el.dispatchEvent(new MouseEvent(eventType, {
                                    bubbles: true,
                                    cancelable: true,
                                    view: window
                                }));
                            });
                        } catch(e) {}
                    });
                } catch(e) {}
            });
        """);

            // AGGRESSIVE: Mouse movements in more patterns
            for (int i = 0; i < 4; i++) {
                int x = 200 + (i * 100);
                int y = 200 + (i * 50);
                page.mouse().move(x, y);
                page.waitForTimeout(200);
            }

            page.waitForTimeout(5000); // Longer wait after interactions
            captureBrowserCookiesEnhanced(context, url, discoveredCookies, transactionId, scanMetrics);
            captureStorageItemsSelectively(page, url, discoveredCookies, transactionId, scanMetrics); // NEW
            log.info("Captured storage after user interactions");

            // === PHASE 5: ANALYTICS EVENT BOMBING ===
            scanMetrics.setScanPhase("TRIGGERING_ANALYTICS");
            log.info("=== PHASE 5: Generic analytics event triggering ===");

            page.evaluate("""
    // GENERIC - Trigger whatever analytics functions exist on the page
    (function() {
        console.log('Starting generic analytics triggering...');
        
        // Common analytics function names to look for
        const analyticsFunctions = [
            'gtag', 'ga', '_gaq', 'fbq', '_fbq', 'analytics', 'track',
            'mixpanel', 'amplitude', '_paq', 'snowplow', 'segment'
        ];
        
        // Try to trigger existing analytics functions
        analyticsFunctions.forEach(funcName => {
            if (typeof window[funcName] === 'function') {
                try {
                    console.log('Found analytics function:', funcName);
                    
                    // Try common event patterns for each function type
                    if (funcName === 'gtag') {
                        window[funcName]('event', 'page_view');
                        window[funcName]('event', 'scroll', {percent_scrolled: 50});
                    } else if (funcName.includes('fb')) {
                        window[funcName]('track', 'PageView');
                    } else if (funcName === 'ga') {
                        window[funcName]('send', 'pageview');
                        window[funcName]('send', 'event', 'engagement', 'scroll', 'generic', 1);
                    } else {
                        // Generic function call
                        window[funcName]('send', 'pageview');
                    }
                } catch(e) {
                    console.log('Error calling', funcName, ':', e.message);
                }
            }
        });
        
        // Generic DOM event triggering
        const events = ['scroll', 'click', 'focus', 'blur', 'load', 'resize', 'beforeunload'];
        events.forEach(eventType => {
            try {
                window.dispatchEvent(new Event(eventType, {bubbles: true}));
            } catch(e) {}
        });
        
        // Trigger any custom tracking events found in page
        if (window.dataLayer && Array.isArray(window.dataLayer)) {
            window.dataLayer.push({'event': 'generic_page_view'});
        }
        
        console.log('Generic analytics triggering completed');
    })();
""");

            page.waitForTimeout(5000);
            captureBrowserCookiesEnhanced(context, url, discoveredCookies, transactionId, scanMetrics);
            captureStorageItemsSelectively(page, url, discoveredCookies, transactionId, scanMetrics); // NEW
            log.info("Captured storage after user interactions");

            // === PHASE 6: SUBDOMAIN AND CROSS-DOMAIN REQUESTS ===
            scanMetrics.setScanPhase("CROSS_DOMAIN_REQUESTS");
            log.info("=== PHASE 6: Cross-domain and subdomain requests ===");

            // Generic subdomain detection for any domain
            // GENERIC - Dynamic subdomain discovery
            String rootDomain = UrlAndCookieUtil.extractRootDomain(url);
//            List<String> discoveredSubdomainHosts = discoverSubdomainsFromPage(page, rootDomain);
//
//            for (String subdomainHost : discoveredSubdomainHosts) {
//                try {
//                    String subdomainUrl = url.startsWith("https") ? "https://" : "http://";
//                    subdomainUrl += subdomainHost;
//
//                    log.info("Trying dynamically discovered subdomain: {}", subdomainUrl);
//                    page.navigate(subdomainUrl, new Page.NavigateOptions().setTimeout(8000));
//                    page.waitForTimeout(2000);
//                    captureBrowserCookiesEnhanced(context, url, discoveredCookies, transactionId, scanMetrics);
//                    break; // Only try first successful one
//                } catch (Exception e) {
//                    log.debug("Failed to visit discovered subdomain: {}", e.getMessage());
//                }
//            }

            // === PHASE 7: HYPERLINK DISCOVERY AND SCANNING ===
//            discoverAndScanHyperlinks(page, url, transactionId, discoveredCookies, scanMetrics);
//
//            try {
//                // use config if you want; otherwise pass a fixed depth
//                int deepDepth = Math.min(DEEP_CRAWL_MAX_DEPTH, 4); // tune as required
//                performDeepCrawl(page, context, url, transactionId, discoveredCookies, scanMetrics, deepDepth);
//            } catch (Exception e) {
//                log.warn("Deep crawl failed: {}", e.getMessage());
//            }


            // === PHASE 8: IFRAME AND EMBED DETECTION ===
            handleIframesAndEmbeds(page, url, transactionId, discoveredCookies, scanMetrics);

            // === NEW PHASE 9: SCAN PROVIDED SUBDOMAINS ===
            // === COMPLIANCE-FOCUSED SUBDOMAIN SCANNING ===
            if (subdomains != null && !subdomains.isEmpty()) {
                // LIMIT: Only scan essential subdomains for compliance
                int maxSubdomainsToScan = Math.min(subdomains.size(), 2); // Maximum 2 subdomains

                scanMetrics.setScanPhase("SCANNING_SUBDOMAINS");
                log.info("=== COMPLIANCE: Scanning {} essential subdomains (limited from {}) ===",
                        maxSubdomainsToScan, subdomains.size());

                for (int i = 0; i < maxSubdomainsToScan; i++) {
                    String subdomain = subdomains.get(i);
                    String subdomainName = SubdomainValidationUtil.extractSubdomainName(subdomain, rootDomain);

                    log.info("=== ESSENTIAL SUBDOMAIN {}/{}: {} (Name: {}) ===",
                            i+1, maxSubdomainsToScan, subdomain, subdomainName);

                    try {
                        // FASTER NAVIGATION: Use DOMCONTENTLOADED instead of NETWORKIDLE
                        Response subdomainResponse = page.navigate(subdomain, new Page.NavigateOptions()
                                .setWaitUntil(WaitUntilState.DOMCONTENTLOADED) // FASTER
                                .setTimeout(15000)); // SHORTER timeout

                        if (subdomainResponse != null && subdomainResponse.ok()) {
                            // SHORTER WAIT: 3 seconds instead of 8
                            page.waitForTimeout(3000);

                            // ESSENTIAL CAPTURES ONLY
                            captureBrowserCookiesWithSubdomainName(context, subdomain, discoveredCookies,
                                    transactionId, scanMetrics, subdomainName);

                            // COMPLIANCE-FOCUSED: Capture storage immediately
                            captureStorageItemsSelectively(page, subdomain, discoveredCookies, transactionId, scanMetrics);

                            // SIMPLIFIED CONSENT: Quick attempt only
                            try {
                                boolean subdomainConsentHandled = CookieDetectionUtil.handleConsentBanners(page, 5000); // SHORTER
                                if (subdomainConsentHandled) {
                                    page.waitForTimeout(2000); // SHORTER wait
                                    captureBrowserCookiesWithSubdomainName(context, subdomain, discoveredCookies,
                                            transactionId, scanMetrics, subdomainName);
                                    captureStorageItemsSelectively(page, subdomain, discoveredCookies, transactionId, scanMetrics);
                                    log.info("Captured cookies after consent on subdomain: {}", subdomainName);
                                }
                            } catch (Exception e) {
                                log.debug("Subdomain consent handling failed for {}: {}", subdomain, e.getMessage());
                            }

                            // SKIP: No extensive scrolling, clicking, analytics bombing
                            // These create noise for compliance scanning

                            log.info("Completed essential scanning of subdomain: {} (Total cookies: {})",
                                    subdomain, discoveredCookies.size());

                        } else {
                            log.warn("Failed to load essential subdomain: {} (Status: {})", subdomain,
                                    subdomainResponse != null ? subdomainResponse.status() : "No response");
                        }
                    } catch (Exception e) {
                        log.warn("Error scanning essential subdomain {}: {}", subdomain, e.getMessage());
                    }
                }

                // INFORMATION: Log what was skipped
                if (subdomains.size() > maxSubdomainsToScan) {
                    log.info("COMPLIANCE: Skipped {} subdomains for focused scanning",
                            subdomains.size() - maxSubdomainsToScan);
                }
            }

            // === AGGRESSIVE FINAL CAPTURE ===
            scanMetrics.setScanPhase("FINAL_CAPTURE");
            log.info("=== AGGRESSIVE: Multiple final cookie captures ===");

            // Capture cookies multiple times with different waits
            int[] waitTimes = {1000, 3000}; // Different wait intervals
            for (int i = 0; i < waitTimes.length; i++) {
                log.info("Final capture pass {} of {}, waiting {}ms", i+1, waitTimes.length, waitTimes[i]);
                page.waitForTimeout(waitTimes[i]);

                int beforeCount = discoveredCookies.size();
                captureBrowserCookiesEnhanced(context, url, discoveredCookies, transactionId, scanMetrics);
                int afterCount = discoveredCookies.size();

                log.info("Final pass {} captured {} new cookies (total: {})",
                        i+1, afterCount - beforeCount, afterCount);
            }

            // FINAL: One more aggressive resource trigger
            page.evaluate("""
            // Final attempt to trigger any remaining tracking
            Array.from(document.querySelectorAll('*')).forEach(el => {
                ['focus', 'blur', 'mouseover', 'mouseout'].forEach(event => {
                    try {
                        el.dispatchEvent(new Event(event, {bubbles: true}));
                    } catch(e) {}
                });
            });
            
            // Final scroll and resize triggers
            window.dispatchEvent(new Event('beforeunload'));
            window.dispatchEvent(new Event('pagehide'));
        """);

            page.waitForTimeout(5000);

            categorizeAllCookiesAtOnce();
            log.info("MAXIMUM DETECTION scan completed. Total unique cookies: {}, Network requests: {}, Subdomains scanned: {}",
                    discoveredCookies.size(), processedUrls.size(), subdomains != null ? subdomains.size() : 0);

        } catch (PlaywrightException e) {
            throw new ScanExecutionException("Playwright error during scan: " + e.getMessage(), e);
        } catch (Exception e) {
            throw new ScanExecutionException("Unexpected error during scan: " + e.getMessage());
        } finally {
            cleanupResources(context, browser, playwright);
        }
    }

    private void performFormInteractionWithStorageCapture(Page page, String url, String transactionId,
                                                          Map<String, CookieDto> discoveredCookies,
                                                          ScanPerformanceTracker.ScanMetrics scanMetrics) {
        try {
            log.info("=== COMPLIANCE: Form interaction with real-time storage capture ===");

            // Find and interact with forms
            page.evaluate("""
            const forms = document.querySelectorAll('form');
            const inputs = document.querySelectorAll('input[type="text"], input[type="email"], input[type="search"]');
            
            forms.forEach(form => {
                try {
                    ['focus', 'mouseover'].forEach(eventType => {
                        form.dispatchEvent(new Event(eventType, {bubbles: true}));
                    });
                } catch(e) {}
            });
            
            inputs.forEach(input => {
                try {
                    input.focus();
                    input.dispatchEvent(new Event('focus', {bubbles: true}));
                    input.dispatchEvent(new Event('input', {bubbles: true}));
                } catch(e) {}
            });
        """);

            page.waitForTimeout(2000);

            // Capture storage after form interactions
            captureStorageItemsSelectively(page, url, discoveredCookies, transactionId, scanMetrics);

        } catch (Exception e) {
            log.debug("Error in form interaction monitoring: {}", e.getMessage());
        }
    }

    // Helper method to determine subdomain name from URL
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

                    String cookieKey = generateCookieKey(cookieDto.getName(), cookieDto.getDomain(), cookieDto.getSubdomainName());

                    if (!discoveredCookies.containsKey(cookieKey)) {
                        discoveredCookies.put(cookieKey, cookieDto);
                        categorizeWithExternalAPI(cookieDto);
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

    // === NEW GENERIC AUTHENTICATION DETECTION ===
    private void detectAndTriggerAuthenticationFlows(Page page, String url, String transactionId,
                                                     Map<String, CookieDto> discoveredCookies,
                                                     ScanPerformanceTracker.ScanMetrics scanMetrics) {
        try {
            log.info("=== GENERIC: Detecting authentication flows ===");

            // Detect and interact with authentication elements
            List<String> authTriggers = (List<String>) page.evaluate("""
            // Generic OAuth and authentication detection
            Array.from(document.querySelectorAll(`
                a[href*="oauth"], a[href*="login"], a[href*="signin"], a[href*="sign-in"],
                a[href*="auth"], a[href*="sso"], a[href*="account"], 
                button[class*="login"], button[class*="signin"], button[class*="auth"],
                [data-provider], [data-oauth], [class*="oauth"], [id*="oauth"],
                [href*="google"], [href*="facebook"], [href*="microsoft"], [href*="linkedin"],
                [href*="twitter"], [href*="github"], [href*="apple"]
            `))
            .map(el => {
                const text = (el.textContent || el.innerText || '').toLowerCase();
                const href = el.href || '';
                const classes = el.className || '';
                
                return {
                    text: text,
                    href: href,
                    type: el.tagName.toLowerCase(),
                    classes: classes
                };
            })
            .filter(item => item.href || item.text.includes('login') || item.text.includes('sign'))
            .slice(0, 10);
        """);

            log.info("Found {} authentication triggers", authTriggers.size());

            // Simulate clicking authentication elements (without completing login)
            page.evaluate("""
            // Generic authentication trigger simulation
            const authSelectors = [
                'a[href*="oauth"]', 'a[href*="login"]', 'a[href*="signin"]',
                'button[class*="login"]', '[data-provider]', '[class*="oauth"]'
            ];
            
            authSelectors.forEach(selector => {
                try {
                    const elements = document.querySelectorAll(selector);
                    Array.from(elements).slice(0, 3).forEach(el => {
                        try {
                            // Trigger authentication-related events
                            ['mouseover', 'mouseenter', 'focus'].forEach(eventType => {
                                el.dispatchEvent(new MouseEvent(eventType, {
                                    bubbles: true,
                                    cancelable: true,
                                    view: window
                                }));
                            });
                            
                            // Scroll element into view to trigger lazy loading
                            el.scrollIntoView({behavior: 'smooth', block: 'center'});
                            
                        } catch(e) {
                            console.debug('Auth trigger error:', e.message);
                        }
                    });
                } catch(e) {}
            });
        """);

            page.waitForTimeout(3000);
            captureBrowserCookiesEnhanced(page.context(), url, discoveredCookies, transactionId, scanMetrics);

        } catch (Exception e) {
            log.debug("Error in authentication flow detection: {}", e.getMessage());
        }
    }

    // === NEW SOCIAL WIDGET DETECTION ===
    private void detectAndTriggerSocialWidgets(Page page, String url, String transactionId,
                                               Map<String, CookieDto> discoveredCookies,
                                               ScanPerformanceTracker.ScanMetrics scanMetrics) {
        try {
            log.info("=== GENERIC: Detecting social widgets ===");

            // Detect social sharing buttons and widgets
            page.evaluate("""
            // Generic social widget detection and triggering
            const socialSelectors = [
                // Facebook
                '.fb-like', '.fb-share', '[data-href*="facebook"]', 'iframe[src*="facebook"]',
                // LinkedIn  
                '.linkedin-share', '[data-provider="linkedin"]', 'iframe[src*="linkedin"]',
                // Twitter
                '.twitter-share', '[data-provider="twitter"]', 'iframe[src*="twitter"]',
                // Generic social
                '[class*="social"]', '[class*="share"]', '[data-social]'
            ];
            
            socialSelectors.forEach(selector => {
                try {
                    const elements = document.querySelectorAll(selector);
                    Array.from(elements).forEach(el => {
                        try {
                            // Trigger social widget events
                            ['load', 'focus', 'mouseover'].forEach(eventType => {
                                el.dispatchEvent(new Event(eventType, {bubbles: true}));
                            });
                            
                            // Scroll into view for lazy loading
                            el.scrollIntoView({behavior: 'smooth', block: 'center'});
                            
                        } catch(e) {}
                    });
                } catch(e) {}
            });
            
            // Force load common social tracking pixels
            const socialDomains = [
                'connect.facebook.net', 'platform.linkedin.com', 'platform.twitter.com'
            ];
            
            socialDomains.forEach(domain => {
                try {
                    const script = document.createElement('script');
                    script.src = 'https://' + domain + '/track?t=' + Date.now();
                    script.onerror = () => {}; // Suppress errors
                    document.head.appendChild(script);
                } catch(e) {}
            });
        """);

            page.waitForTimeout(4000);
            captureBrowserCookiesEnhanced(page.context(), url, discoveredCookies, transactionId, scanMetrics);

        } catch (Exception e) {
            log.debug("Error in social widget detection: {}", e.getMessage());
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
                    String cookieKey = generateCookieKey(cookieDto.getName(), cookieDto.getDomain(), cookieDto.getSubdomainName());

                    if (!discoveredCookies.containsKey(cookieKey)) {
                        discoveredCookies.put(cookieKey, cookieDto);
                        categorizeWithExternalAPI(cookieDto);
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
            // AGGRESSIVE: Try multiple parsing strategies

            // Strategy 1: Standard comma-separated parsing
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

            // Strategy 2: If no cookies found, try semicolon separation
            if (cookies.isEmpty()) {
                String[] semiColonParts = cookieHeader.split(";");
                for (String part : semiColonParts) {
                    if (part.contains("=")) {
                        CookieDto cookie = parseSetCookieHeader(part.trim(), scanUrl, responseDomain, source);
                        if (cookie != null) {
                            cookies.add(cookie);
                        }
                    }
                }
            }

            // Strategy 3: If still no cookies, try space separation
            if (cookies.isEmpty()) {
                String[] spaceParts = cookieHeader.split("\\s+");
                for (String part : spaceParts) {
                    if (part.contains("=") && !part.contains("http")) {
                        CookieDto cookie = parseSetCookieHeader(part.trim(), scanUrl, responseDomain, source);
                        if (cookie != null) {
                            cookies.add(cookie);
                        }
                    }
                }
            }

            // Strategy 4: If header doesn't look like standard cookie, create generic cookie
//            if (cookies.isEmpty() && cookieHeader.length() > 0 && cookieHeader.length() < 100) {
//                // Might be a tracking ID or session token
//                String cookieName = "header_value_" + Math.abs(cookieHeader.hashCode());
//                CookieDto genericCookie = new CookieDto(
//                        cookieName, scanUrl, responseDomain, "/", null,
//                        false, false, null, source
//                );
//                genericCookie.setDescription("Non-standard header value: " + cookieHeader.substring(0, Math.min(50, cookieHeader.length())));
//                cookies.add(genericCookie);
//            }

        } catch (Exception e) {
            log.warn("Error parsing cookie headers: {}", e.getMessage());

            // Fallback: Create a single cookie from the entire header
            CookieDto fallbackCookie = parseSetCookieHeader(cookieHeader, scanUrl, responseDomain, source);
            if (fallbackCookie != null) {
                cookies.add(fallbackCookie);
            }
        }

        return cookies;
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
                // Initialize grouped structure if needed
                if (result.getCookiesBySubdomain() == null) {
                    result.setCookiesBySubdomain(new HashMap<>());
                }

                String subdomainName = cookieDto.getSubdomainName() != null ? cookieDto.getSubdomainName() : "main";

                // Get or create subdomain cookie list
                List<CookieEntity> subdomainCookies = result.getCookiesBySubdomain()
                        .computeIfAbsent(subdomainName, k -> new ArrayList<>());

                // Check for duplicates within this subdomain
                boolean exists = subdomainCookies.stream()
                        .anyMatch(c -> c.getName().equals(cookieDto.getName()) &&
                                Objects.equals(c.getDomain(), cookieDto.getDomain()));

                if (!exists) {
                    CookieEntity cookieEntity = ScanResultMapper.cookieDtoToEntity(cookieDto);
                    subdomainCookies.add(cookieEntity);
                    repository.save(result);

                    log.debug("✅ Saved cookie: {} to subdomain: {}", cookieDto.getName(), subdomainName);
                } else {
                    log.debug("⚠️ Duplicate cookie skipped: {} in subdomain: {}", cookieDto.getName(), subdomainName);
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

    private String generateCookieKey(String name, String domain, String subdomainName) {
        return name + "@" + (domain != null ? domain.toLowerCase() : "") +
                "#" + (subdomainName != null ? subdomainName : "main");
    }

    private void handleIframesAndEmbeds(Page page, String url, String transactionId,
                                        Map<String, CookieDto> discoveredCookies,
                                        ScanPerformanceTracker.ScanMetrics scanMetrics) {
        try {
            scanMetrics.setScanPhase("IFRAME_DETECTION");
            log.info("=== PHASE 8: ENHANCED iframe/embed detection ===");

            page.waitForTimeout(3000);

            List<IframeInfo> iframes = detectAllIframes(page);
            log.info("Found {} iframes/embeds on page", iframes.size());

            // ENHANCED: Better frame processing
            for (Frame frame : page.frames()) {
                try {
                    String frameUrl = frame.url();
                    if (frameUrl == null || frameUrl.equals("about:blank") || frameUrl.equals(url)) {
                        continue;
                    }

                    log.info("Processing frame: {}", frameUrl);

                    try {
                        frame.waitForLoadState(LoadState.NETWORKIDLE,
                                new Frame.WaitForLoadStateOptions().setTimeout(10000));
                    } catch (Exception e) {
                        log.debug("Frame load timeout: {}", frameUrl);
                    }

                    // ENHANCED: Generic authentication iframe detection
                    try {
                        frame.evaluate("""
                        // Generic authentication detection within iframe
                        if (window.location.hostname.includes('login') || 
                            window.location.hostname.includes('auth') ||
                            window.location.hostname.includes('oauth') ||
                            document.title.toLowerCase().includes('sign') ||
                            document.title.toLowerCase().includes('login')) {
                            
                            // Trigger authentication events
                            window.dispatchEvent(new Event('load'));
                            window.dispatchEvent(new Event('focus'));
                            
                            // Try to find and interact with auth elements
                            const authElements = document.querySelectorAll(`
                                button, input[type="submit"], [role="button"],
                                a[href*="continue"], a[href*="next"]
                            `);
                            
                            authElements.forEach(el => {
                                try {
                                    ['mouseover', 'focus'].forEach(eventType => {
                                        el.dispatchEvent(new MouseEvent(eventType, {
                                            bubbles: true, cancelable: true, view: window
                                        }));
                                    });
                                } catch(e) {}
                            });
                        }
                    """);
                    } catch (Exception e) {
                        // Cross-origin iframe, ignore
                    }

                    // Capture cookies from this frame
                    List<Cookie> frameCookies = page.context().cookies(frameUrl);
                    String frameRoot = UrlAndCookieUtil.extractRootDomain(frameUrl);
                    String siteRoot = UrlAndCookieUtil.extractRootDomain(url);
                    Source frameSource = siteRoot.equalsIgnoreCase(frameRoot) ? Source.FIRST_PARTY : Source.THIRD_PARTY;

                    for (Cookie frameCookie : frameCookies) {
                        CookieDto cookieDto = mapPlaywrightCookie(frameCookie, frameUrl, frameRoot);
                        cookieDto.setSource(frameSource);

                        String subdomainName = determineSubdomainNameFromUrl(frameUrl, url, null);
                        cookieDto.setSubdomainName(subdomainName);

                        String cookieKey = generateCookieKey(cookieDto.getName(), cookieDto.getDomain(), cookieDto.getSubdomainName());
                        if (!discoveredCookies.containsKey(cookieKey)) {
                            discoveredCookies.put(cookieKey, cookieDto);
                            categorizeWithExternalAPI(cookieDto);
                            saveIncrementalCookieWithFlush(transactionId, cookieDto);
                            scanMetrics.incrementCookiesFound(frameSource.name());
                            metrics.recordCookieDiscovered(frameSource.name());
                            log.info("ADDED FRAME COOKIE: {} from {} (Source: {})",
                                    cookieDto.getName(), frameUrl, frameSource);
                        }
                    }

                } catch (Exception e) {
                    log.debug("Error processing frame {}", e.getMessage());
                }
            }

            // Continue with existing iframe processing
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
            log.warn("Error during enhanced iframe detection: {}", e.getMessage());
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

    private void performDeepCrawl(Page page,
                                  BrowserContext context,
                                  String baseUrl,
                                  String transactionId,
                                  Map<String, CookieDto> discoveredCookies,
                                  ScanPerformanceTracker.ScanMetrics scanMetrics,
                                  int maxDepth) {
        scanMetrics.setScanPhase("DEEP_CRAWL");
        log.info("=== PHASE: DEEP CRAWL (maxDepth={}) ===", maxDepth);

        final Set<String> visited = ConcurrentHashMap.newKeySet();
        final Map<String, Integer> pagesPerHost = new ConcurrentHashMap<>();
        final Deque<UrlDepth> stack = new ArrayDeque<>();
        stack.push(new UrlDepth(baseUrl, 0));

        String rootDomain = UrlAndCookieUtil.extractRootDomain(baseUrl);
        String originalUrl = page.url();

        try {
            while (!stack.isEmpty()) {
                UrlDepth current = stack.pop();
                if (current.depth > maxDepth) continue;
                if (visited.size() >= DEEP_CRAWL_MAX_PAGES) break;
                String curUrl = current.url;

                if (visited.contains(curUrl)) continue;

                java.net.URL parsed;
                try {
                    parsed = new java.net.URL(curUrl.startsWith("http") ? curUrl : ("https://" + curUrl));
                } catch (Exception e) {
                    log.debug("Skipping invalid URL during deep crawl: {}", curUrl);
                    continue;
                }

                String host = parsed.getHost().toLowerCase();
                pagesPerHost.putIfAbsent(host, 0);
                if (pagesPerHost.get(host) >= DEEP_CRAWL_MAX_PAGES_PER_HOST) {
                    log.debug("Skipping {} because host limit reached for {}", curUrl, host);
                    continue;
                }

                try {
                    log.info("Deep crawling (depth={}): {}", current.depth, curUrl);
                    Response resp = page.navigate(curUrl,
                            new Page.NavigateOptions().setWaitUntil(WaitUntilState.DOMCONTENTLOADED).setTimeout(20000));
                    // small wait to let JS fire resources
                    page.waitForTimeout(2000);

                    visited.add(curUrl);
                    pagesPerHost.computeIfPresent(host, (k, v) -> v + 1);

                    // Capture runtime cookies (browser store)
                    captureBrowserCookiesEnhanced(context, curUrl, discoveredCookies, transactionId, scanMetrics);

                    // Capture local/session storage which sometimes holds id tokens / digital caches
                    //captureStorageItems(page, curUrl, discoveredCookies, transactionId, scanMetrics);

                    // Let existing context.onResponse pick up Set-Cookie headers for 3rd-party responses
                    // Now extract links to continue crawling
                    List<String> childLinks = extractLinksForCrawl(page, rootDomain, LINKS_PER_PAGE);
                    for (String l : childLinks) {
                        if (!visited.contains(l)) {
                            stack.push(new UrlDepth(l, current.depth + 1));
                        }
                    }

                } catch (Exception e) {
                    log.debug("Deep crawl navigation failed for {}: {}", curUrl, e.getMessage());
                }
            } // while

        } finally {
            try {
                // try to go back to original page so rest of scan can continue from where it left
                if (originalUrl != null && !originalUrl.isEmpty()) {
                    try {
                        page.navigate(originalUrl, new Page.NavigateOptions().setTimeout(10000));
                        page.waitForTimeout(1000);
                    } catch (Exception ignored) {}
                }
            } catch (Exception e) {
                // ignore
            }
            log.info("Deep crawl finished. Visited {} pages (rootDomain={})", visited.size(), rootDomain);
        }
    }

    // Extract links for crawling (anchors, images, scripts, iframes)
    @SuppressWarnings("unchecked")
    private List<String> extractLinksForCrawl(Page page, String rootDomain, int limit) {
        try {
            List<String> all = (List<String>) page.evaluate(String.format("""
            Array.from(document.querySelectorAll('a[href], area[href], img[src], iframe[src], script[src], link[href]'))
                .map(el => el.href || el.src || el.getAttribute('href'))
                .filter(u => u && (u.startsWith('http') || u.startsWith('https')))
                .slice(0, %d);
            """, limit));

            // Normalize and keep only http(s)
            return all.stream()
                    .filter(Objects::nonNull)
                    .map(u -> {
                        try { return new java.net.URL(u).toString(); }
                        catch (Exception ex) { return null; }
                    })
                    .filter(Objects::nonNull)
                    .collect(Collectors.toList());
        } catch (Exception e) {
            log.debug("Error extracting links for crawl: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    // Capture localStorage & sessionStorage into your cookie model (will be saved and categorized like cookies)
//    @SuppressWarnings("unchecked")
//    private void captureStorageItems(Page page,
//                                     String scanUrl,
//                                     Map<String, CookieDto> discoveredCookies,
//                                     String transactionId,
//                                     ScanPerformanceTracker.ScanMetrics scanMetrics) {
//        try {
//            Object evalRes = page.evaluate("""
//            (() => {
//                const out = { local: {}, session: {} };
//                try {
//                    for (let i = 0; i < localStorage.length; i++) {
//                        const k = localStorage.key(i);
//                        out.local[k] = localStorage.getItem(k);
//                    }
//                } catch(e) {}
//                try {
//                    for (let i = 0; i < sessionStorage.length; i++) {
//                        const k = sessionStorage.key(i);
//                        out.session[k] = sessionStorage.getItem(k);
//                    }
//                } catch(e) {}
//                return out;
//            })();
//        """);
//
//            if (!(evalRes instanceof Map)) return;
//            Map<String, Object> storageMap = (Map<String, Object>) evalRes;
//            Map<String, Object> local = (Map<String, Object>) storageMap.getOrDefault("local", Collections.emptyMap());
//            Map<String, Object> session = (Map<String, Object>) storageMap.getOrDefault("session", Collections.emptyMap());
//
//            String siteRoot = UrlAndCookieUtil.extractRootDomain(scanUrl);
//            String subdomainName = SubdomainValidationUtil.extractSubdomainName(scanUrl, siteRoot);
//
//            // Save localStorage items
//            for (Map.Entry<String, Object> e : local.entrySet()) {
//                try {
//                    String key = e.getKey();
//                    String val = e.getValue() != null ? e.getValue().toString() : "";
//                    String name = "localStorage:" + key;
//                    String cookieKey = generateCookieKey(name, siteRoot, subdomainName);
//                    if (!discoveredCookies.containsKey(cookieKey)) {
//                        CookieDto dto = new CookieDto(name, scanUrl, siteRoot, "/", null, false, false, null, Source.UNKNOWN);
//                        dto.setDescription(val); // store raw value in description to inspect later
//                        dto.setSubdomainName(subdomainName);
//                        discoveredCookies.put(cookieKey, dto);
//                        categorizeWithExternalAPI(dto);
//                        saveIncrementalCookieWithFlush(transactionId, dto);
//                        scanMetrics.incrementCookiesFound(dto.getSource().name());
//                        metrics.recordCookieDiscovered(dto.getSource().name());
//                        log.info("ADDED localStorage item as cookie-like: {} (subdomain {})", name, subdomainName);
//                    }
//                } catch (Exception ignored) {}
//            }
//
//            // Save sessionStorage items
//            for (Map.Entry<String, Object> e : session.entrySet()) {
//                try {
//                    String key = e.getKey();
//                    String val = e.getValue() != null ? e.getValue().toString() : "";
//                    String name = "sessionStorage:" + key;
//                    String cookieKey = generateCookieKey(name, siteRoot, subdomainName);
//                    if (!discoveredCookies.containsKey(cookieKey)) {
//                        CookieDto dto = new CookieDto(name, scanUrl, siteRoot, "/", null, false, false, null, Source.UNKNOWN);
//                        dto.setDescription(val);
//                        dto.setSubdomainName(subdomainName);
//                        discoveredCookies.put(cookieKey, dto);
//                        categorizeWithExternalAPI(dto);
//                        saveIncrementalCookieWithFlush(transactionId, dto);
//                        scanMetrics.incrementCookiesFound(dto.getSource().name());
//                        metrics.recordCookieDiscovered(dto.getSource().name());
//                        log.info("ADDED sessionStorage item as cookie-like: {} (subdomain {})", name, subdomainName);
//                    }
//                } catch (Exception ignored) {}
//            }
//
//        } catch (Exception e) {
//            log.debug("Error capturing storage items for {}: {}", scanUrl, e.getMessage());
//        }
//    }

    private List<String> discoverPriorityLinks(Page page, String rootDomain) {
        Set<String> priorityLinks = new LinkedHashSet<>();

        try {
            // GENERIC - Use discovered important pages instead of hardcoded patterns
            List<String> importantPages = discoverImportantPages(page, rootDomain);
            priorityLinks.addAll(importantPages);

// Also add any links that contain common important keywords
            try {
                List<String> keywordLinks = (List<String>) page.evaluate("""
        Array.from(document.querySelectorAll('a[href]'))
            .filter(a => {
                const href = a.href.toLowerCase();
                const text = (a.textContent || '').toLowerCase();
                const title = (a.title || '').toLowerCase();
                
                return ['login', 'account', 'profile', 'dashboard', 'settings', 
                        'checkout', 'cart', 'register', 'signup'].some(keyword => 
                    href.includes(keyword) || text.includes(keyword) || title.includes(keyword)
                );
            })
            .map(a => a.href)
            .filter(href => href.startsWith('http'))
            .slice(0, 5);
    """);

                for (String link : keywordLinks) {
                    String linkDomain = UrlAndCookieUtil.extractRootDomain(link);
                    if (rootDomain.equals(linkDomain)) {
                        priorityLinks.add(link);
                        if (priorityLinks.size() >= 10) break;
                    }
                }

            } catch (Exception e) {
                log.debug("Error finding keyword-based links: {}", e.getMessage());
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

    // Don't remove(External Predict API call)
    private void categorizeWithExternalAPI(CookieDto cookie) {
        // Just store the cookie for later categorization
        cookiesAwaitingCategorization.put(cookie.getName(), cookie);
        log.debug("Stored cookie '{}' for categorization. Total stored: {}",
                cookie.getName(), cookiesAwaitingCategorization.size());
    }

    private void categorizeAllCookiesAtOnce() {
        if (cookiesAwaitingCategorization.isEmpty()) {
            log.info("No cookies found to categorize");
            return;
        }

        try {
            List<String> cookieNames = new ArrayList<>(cookiesAwaitingCategorization.keySet());
            log.info("Categorizing {} cookies with ONE API call", cookieNames.size());

            // Make single API call for all cookies
            Map<String, CookieCategorizationResponse> results =
                    cookieCategorizationService.categorizeCookies(cookieNames);

            // Apply categorization results to cookies
            for (Map.Entry<String, CookieCategorizationResponse> entry : results.entrySet()) {
                String cookieName = entry.getKey();
                CookieCategorizationResponse response = entry.getValue();
                CookieDto cookie = cookiesAwaitingCategorization.get(cookieName);

                if (cookie != null && response != null) {
                    cookie.setCategory(response.getCategory());
                    cookie.setDescription(response.getDescription());
                    cookie.setDescription_gpt(response.getDescription_gpt());
                }
            }

            log.info("Successfully categorized {} cookies", cookieNames.size());
            cookiesAwaitingCategorization.clear();

        } catch (Exception e) {
            log.error("Cookie categorization failed: {}", e.getMessage());
            cookiesAwaitingCategorization.clear();
        }
    }

    private List<String> discoverSubdomainsFromPage(Page page, String rootDomain) {
        try {
            log.debug("Discovering subdomains dynamically for domain: {}", rootDomain);

            List<String> discoveredSubdomains = (List<String>) page.evaluate(String.format("""
            Array.from(document.querySelectorAll('a[href], script[src], link[href], img[src], iframe[src]'))
                .map(el => el.href || el.src || el.getAttribute('href') || el.getAttribute('src'))
                .filter(url => url && typeof url === 'string' && (url.startsWith('http') || url.startsWith('https')))
                .map(url => {
                    try { 
                        return new URL(url).hostname.toLowerCase(); 
                    } catch(e) { 
                        return null; 
                    }
                })
                .filter(host => host && typeof host === 'string' && host.includes('.'))
                .filter(host => host.endsWith('.%s') && host !== '%s')
                .filter((host, index, arr) => arr.indexOf(host) === index)
                .slice(0, 10);
            """, rootDomain, rootDomain));

            log.info("Discovered {} dynamic subdomains: {}", discoveredSubdomains.size(), discoveredSubdomains);
            return discoveredSubdomains;

        } catch (Exception e) {
            log.debug("Error discovering subdomains dynamically: {}", e.getMessage());
            return Collections.emptyList();
        }
    }
    /**
     * GENERIC - Discover important pages from navigation instead of hardcoded patterns
     */
    private List<String> discoverImportantPages(Page page, String rootDomain) {
        try {
            log.debug("Discovering important pages dynamically for domain: {}", rootDomain);

            List<String> importantPages = (List<String>) page.evaluate("""
            // Look for navigation links and important sections
            Array.from(document.querySelectorAll(`
                nav a, .nav a, .navigation a, .navbar a,
                .menu a, .main-menu a, header a,
                .footer a, .important a, .primary a,
                [role="navigation"] a, .site-nav a,
                .top-menu a, .breadcrumb a
            `))
            .map(a => a.href)
            .filter(href => href && (href.startsWith('http') || href.startsWith('https')))
            .filter((href, index, arr) => arr.indexOf(href) === index)
            .slice(0, 15);
        """);

            // Filter to same domain only
            List<String> sameDomainPages = new ArrayList<>();
            for (String pageUrl : importantPages) {
                try {
                    String pageDomain = UrlAndCookieUtil.extractRootDomain(pageUrl);
                    if (rootDomain.equalsIgnoreCase(pageDomain)) {
                        sameDomainPages.add(pageUrl);
                        if (sameDomainPages.size() >= 10) break;
                    }
                } catch (Exception e) {
                    // Skip invalid URLs
                }
            }

            log.info("Discovered {} important pages: {}", sameDomainPages.size(), sameDomainPages);
            return sameDomainPages;

        } catch (Exception e) {
            log.debug("Error discovering important pages: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    private boolean isTrackingResponse(String url) {
        String lowerUrl = url.toLowerCase();

        // Check file extensions from config
        boolean hasTrackingExtension = trackingPatterns.getUrlPatterns()
                .getFileExtensions().stream()
                .anyMatch(lowerUrl::contains);

        // Check endpoints from config
        boolean hasTrackingEndpoint = trackingPatterns.getUrlPatterns()
                .getEndpoints().stream()
                .anyMatch(lowerUrl::contains);

        // Check single character files from config
        boolean hasSingleCharFile = trackingPatterns.getUrlPatterns()
                .getSingleCharFiles().stream()
                .anyMatch(pattern -> lowerUrl.matches(".*" + pattern + ".*"));

        // Check domain prefixes from config
        boolean hasTrackingDomainPrefix = trackingPatterns.getUrlPatterns()
                .getDomainPrefixes().stream()
                .anyMatch(lowerUrl::contains);

        // Check special patterns from config
        boolean hasSpecialPattern = trackingPatterns.getUrlPatterns()
                .getSpecialPatterns().stream()
                .anyMatch(lowerUrl::contains);

        // Check if URL has parameters (potential tracking)
        boolean hasParameters = (lowerUrl.contains("?") || lowerUrl.contains("&")) &&
                (lowerUrl.contains("="));

        // Check for single letter subdomains (like c.bing.com)
        boolean hasSingleLetterSubdomain = url.matches("https?://[a-z]\\.[a-z]+\\.[a-z]+.*");

        // Check for numeric paths
        boolean hasNumericPath = url.matches(".*://[^/]+/\\d+$");

        return hasTrackingExtension || hasTrackingEndpoint || hasSingleCharFile ||
                hasTrackingDomainPrefix || hasSpecialPattern || hasParameters ||
                hasSingleLetterSubdomain || hasNumericPath;
    }

    // GENERIC: Extract tracking data from any URL parameters
    private void extractTrackingDataFromUrl(String responseUrl, String mainUrl,
                                            Map<String, CookieDto> discoveredCookies,
                                            String transactionId,
                                            ScanPerformanceTracker.ScanMetrics scanMetrics,
                                            List<String> subdomains) {
        try {
            java.net.URL parsedUrl = new java.net.URL(responseUrl);
            String query = parsedUrl.getQuery();
            if (query != null && !query.isEmpty()) {
                String[] params = query.split("&");
                for (String param : params) {
                    if (param.contains("=")) {
                        String[] parts = param.split("=", 2);
                        String paramName = parts[0];
                        String paramValue = parts.length > 1 ? parts[1] : "";

                        // Use config patterns to detect tracking parameters
                        if (looksLikeTrackingParameter(paramName, paramValue)) {
                            log.info("GENERIC TRACKING PARAM: {} = {}", paramName, paramValue);

                            String cookieName = "param_" + paramName;
                            String responseDomain = parsedUrl.getHost();
                            String siteRoot = UrlAndCookieUtil.extractRootDomain(mainUrl);
                            String responseRoot = UrlAndCookieUtil.extractRootDomain(responseUrl);
                            Source source = siteRoot.equalsIgnoreCase(responseRoot) ? Source.FIRST_PARTY : Source.THIRD_PARTY;

                            CookieDto trackingCookie = new CookieDto(
                                    cookieName, responseUrl, responseDomain, "/", null,
                                    false, false, null, source
                            );
                            trackingCookie.setDescription("Tracking parameter: " + paramValue);
                            String subdomainName = determineSubdomainNameFromUrl(responseUrl, mainUrl, subdomains);
                            trackingCookie.setSubdomainName(subdomainName);

//                            String cookieKey = generateCookieKey(cookieName, responseDomain, subdomainName);
//                            if (!discoveredCookies.containsKey(cookieKey)) {
//                                discoveredCookies.put(cookieKey, trackingCookie);
//                                categorizeWithExternalAPI(trackingCookie);
//                                saveIncrementalCookieWithFlush(transactionId, trackingCookie);
//                                scanMetrics.incrementCookiesFound(source.name());
//                                metrics.recordCookieDiscovered(source.name());
//                                log.info("ADDED GENERIC TRACKING PARAM: {} from {}",
//                                        cookieName, responseUrl);
//                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.debug("Error extracting generic tracking data: {}", e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private void captureStorageItemsSelectively(Page page,
                                                String scanUrl,
                                                Map<String, CookieDto> discoveredCookies,
                                                String transactionId,
                                                ScanPerformanceTracker.ScanMetrics scanMetrics) {
        try {
            log.info("=== SELECTIVE: Capturing tracking-pattern storage items ===");

            // Get ALL storage items first (no filtering in JavaScript)
            Object evalRes = page.evaluate("""
            (() => {
                const out = { local: {}, session: {} };
                try {
                    for (let i = 0; i < localStorage.length; i++) {
                        const k = localStorage.key(i);
                        out.local[k] = localStorage.getItem(k);
                    }
                } catch(e) {}
                
                try {
                    for (let i = 0; i < sessionStorage.length; i++) {
                        const k = sessionStorage.key(i);
                        out.session[k] = sessionStorage.getItem(k);
                    }
                } catch(e) {}
                
                return out;
            })();
        """);

            if (!(evalRes instanceof Map)) return;
            Map<String, Object> storageMap = (Map<String, Object>) evalRes;
            Map<String, Object> local = (Map<String, Object>) storageMap.getOrDefault("local", Collections.emptyMap());
            Map<String, Object> session = (Map<String, Object>) storageMap.getOrDefault("session", Collections.emptyMap());

            String siteRoot = UrlAndCookieUtil.extractRootDomain(scanUrl);
            String subdomainName = SubdomainValidationUtil.extractSubdomainName(scanUrl, siteRoot);

            // NOW filter on the Java side using your config
            processStorageItemsWithConfigFiltering(local, "localStorage", scanUrl, siteRoot, subdomainName, discoveredCookies, transactionId, scanMetrics);
            processStorageItemsWithConfigFiltering(session, "sessionStorage", scanUrl, siteRoot, subdomainName, discoveredCookies, transactionId, scanMetrics);

            log.info("Config-based storage capture completed");

        } catch (Exception e) {
            log.debug("Error capturing storage items: {}", e.getMessage());
        }
    }

    private void processStorageItemsWithConfigFiltering(Map<String, Object> items, String storageType, String scanUrl,
                                                        String siteRoot, String subdomainName, Map<String, CookieDto> discoveredCookies,
                                                        String transactionId, ScanPerformanceTracker.ScanMetrics scanMetrics) {
        for (Map.Entry<String, Object> entry : items.entrySet()) {
            try {
                String key = entry.getKey();

                // USE YOUR CONFIG HERE - this is where the real filtering happens
                if (!isTrackingPattern(key, storagePatternsConfig)) {
                    log.debug("Skipping non-tracking storage item: {}", key);
                    continue;
                }

                String val = entry.getValue() != null ? entry.getValue().toString() : "";
                String cookieKey = generateCookieKey(key, siteRoot, subdomainName);

                if (!discoveredCookies.containsKey(cookieKey)) {
                    CookieDto dto = new CookieDto(key, scanUrl, siteRoot, "/", null, false, false, null, Source.FIRST_PARTY);
                    dto.setDescription(storageType + ": " + (val.length() > 50 ? val.substring(0, 50) + "..." : val));
                    dto.setSubdomainName(subdomainName);
                    discoveredCookies.put(cookieKey, dto);
                    categorizeWithExternalAPI(dto);
                    saveIncrementalCookieWithFlush(transactionId, dto);
                    scanMetrics.incrementCookiesFound(dto.getSource().name());
                    metrics.recordCookieDiscovered(dto.getSource().name());
                    log.info("ADDED CONFIG-FILTERED {}: {} (subdomain {})", storageType, key, subdomainName);
                }
            } catch (Exception ignored) {}
        }
    }

    private boolean isTrackingPattern(String key, StoragePatternsConfig config) {
        if (key == null || key.trim().isEmpty()) return false;

        if (config == null) {
            log.warn("StoragePatternsConfig is null, falling back to basic patterns");
            return key.length() <= 5 || key.startsWith("_");
        }

        // Check known tracking keys (direct property)
        if (config.getKnownTrackingKeys() != null) {
            for (String knownKey : config.getKnownTrackingKeys()) {
                if (key.contains(knownKey)) {
                    log.debug("Key '{}' matches known tracking key: {}", key, knownKey);
                    return true;
                }
            }
        }

        var generic = config.getGeneric();
        if (generic == null) {
            log.debug("Generic patterns not configured");
            return false;
        }

        // Check short keys
        var shortKeys = generic.getShortKeys();
        if (shortKeys != null) {
            int keyLength = key.length();
            if (keyLength >= shortKeys.getMinLength() && keyLength <= shortKeys.getMaxLength()) {
                log.debug("Key '{}' matches short key pattern (length: {})", key, keyLength);
                return true;
            }
        }

        // Check regex patterns
        if (generic.getRegexPatterns() != null) {
            for (String pattern : generic.getRegexPatterns()) {
                try {
                    if (key.matches(pattern)) {
                        log.debug("Key '{}' matches regex pattern: {}", key, pattern);
                        return true;
                    }
                } catch (Exception e) {
                    log.warn("Invalid regex pattern '{}': {}", pattern, e.getMessage());
                }
            }
        }

        // Check keyword patterns
        if (generic.getKeywordPatterns() != null) {
            String lowerKey = key.toLowerCase();
            for (String keyword : generic.getKeywordPatterns()) {
                if (lowerKey.contains(keyword.toLowerCase())) {
                    log.debug("Key '{}' matches keyword pattern: {}", key, keyword);
                    return true;
                }
            }
        }

        // Check prefix patterns
        if (generic.getPrefixPatterns() != null) {
            for (String prefix : generic.getPrefixPatterns()) {
                if (key.startsWith(prefix)) {
                    log.debug("Key '{}' matches prefix pattern: {}", key, prefix);
                    return true;
                }
            }
        }

        log.debug("Key '{}' does not match any tracking patterns", key);
        return false;
    }

    private void processStorageItems(Map<String, Object> items, String storageType, String scanUrl,
                                     String siteRoot, String subdomainName, Map<String, CookieDto> discoveredCookies,
                                     String transactionId, ScanPerformanceTracker.ScanMetrics scanMetrics) {
        for (Map.Entry<String, Object> entry : items.entrySet()) {
            try {
                String key = entry.getKey();
                String val = entry.getValue() != null ? entry.getValue().toString() : "";
                String cookieKey = generateCookieKey(key, siteRoot, subdomainName);

                if (!discoveredCookies.containsKey(cookieKey)) {
                    CookieDto dto = new CookieDto(key, scanUrl, siteRoot, "/", null, false, false, null, Source.FIRST_PARTY);
                    dto.setDescription(storageType + ": " + (val.length() > 50 ? val.substring(0, 50) + "..." : val));
                    dto.setSubdomainName(subdomainName);
                    discoveredCookies.put(cookieKey, dto);
                    categorizeWithExternalAPI(dto);
                    saveIncrementalCookieWithFlush(transactionId, dto);
                    scanMetrics.incrementCookiesFound(dto.getSource().name());
                    metrics.recordCookieDiscovered(dto.getSource().name());
                    log.info("ADDED GENERIC {}: {} (subdomain {})", storageType, key, subdomainName);
                }
            } catch (Exception ignored) {}
        }
    }

    private boolean looksLikeTrackingParameter(String name, String value) {
        String lowerName = name.toLowerCase();

        // Check all parameter pattern categories from config
        boolean matchesIdPattern = trackingPatterns.getParameterPatterns()
                .getIdPatterns().stream()
                .anyMatch(pattern -> lowerName.contains(pattern.toLowerCase()));

        boolean matchesSessionAuth = trackingPatterns.getParameterPatterns()
                .getSessionAuth().stream()
                .anyMatch(pattern -> lowerName.contains(pattern.toLowerCase()));

        boolean matchesTrackingAnalytics = trackingPatterns.getParameterPatterns()
                .getTrackingAnalytics().stream()
                .anyMatch(pattern -> lowerName.contains(pattern.toLowerCase()));

        boolean matchesPageContent = trackingPatterns.getParameterPatterns()
                .getPageContent().stream()
                .anyMatch(pattern -> lowerName.contains(pattern.toLowerCase()));

        boolean matchesUserDevice = trackingPatterns.getParameterPatterns()
                .getUserDevice().stream()
                .anyMatch(pattern -> lowerName.contains(pattern.toLowerCase()));

        boolean matchesShortCryptic = trackingPatterns.getParameterPatterns()
                .getShortCryptic().stream()
                .anyMatch(pattern -> lowerName.equals(pattern.toLowerCase()));

        boolean matchesTimePattern = trackingPatterns.getParameterPatterns()
                .getTimePatterns().stream()
                .anyMatch(pattern -> lowerName.contains(pattern.toLowerCase()));

        boolean matchesRandomCache = trackingPatterns.getParameterPatterns()
                .getRandomCache().stream()
                .anyMatch(pattern -> lowerName.contains(pattern.toLowerCase()));

        // Check value patterns from config
        boolean matchesValuePattern = trackingPatterns.getValuePatterns()
                .getRegexPatterns().stream()
                .anyMatch(pattern -> value.matches(pattern));

        boolean matchesBooleanValue = trackingPatterns.getValuePatterns()
                .getBooleanValues().stream()
                .anyMatch(pattern -> value.equalsIgnoreCase(pattern));

        // Basic size check
        boolean hasValidSize = (name.length() >= 1 && value.length() >= 1);

        return hasValidSize && (matchesIdPattern || matchesSessionAuth || matchesTrackingAnalytics ||
                matchesPageContent || matchesUserDevice || matchesShortCryptic ||
                matchesTimePattern || matchesRandomCache || matchesValuePattern ||
                matchesBooleanValue);
    }

    private void testStoragePatterns() {
        log.info("=== TESTING STORAGE PATTERNS ===");

        String[] testKeys = {"s7", "_c", "hsb", "12345", "ABC", "track_id", "$session", "verylongkeyname"};

        for (String testKey : testKeys) {
            boolean matches = isTrackingPattern(testKey, storagePatternsConfig);
            log.info("Pattern test: '{}' -> {}", testKey, matches);
        }
    }
}