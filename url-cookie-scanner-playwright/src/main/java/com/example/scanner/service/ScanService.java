package com.example.scanner.service;

import com.example.scanner.constants.ErrorCodes;
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
import com.microsoft.playwright.options.LoadState;
import com.microsoft.playwright.options.WaitUntilState;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.net.URL;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;

import java.util.List;

@Service
public class ScanService {

    private static final Logger log = LoggerFactory.getLogger(ScanService.class);

    @Autowired
    @Qualifier("scanCircuitBreaker")
    private CircuitBreaker scanCircuitBreaker;

    private final ScanResultRepository repository;
    private final CookieCategorizationService cookieCategorizationService;
    private final CookieScanMetrics metrics;
    private final Map<String, CookieDto> cookiesAwaitingCategorization = new ConcurrentHashMap<>();

    @Lazy
    @Autowired
    private ScanService self;

    public ScanService(ScanResultRepository repository,
                       CookieCategorizationService cookieCategorizationService,
                       CookieScanMetrics metrics){
        this.repository = repository;
        this.cookieCategorizationService = cookieCategorizationService;
        this.metrics = metrics;
    }

    public String startScanWithProtection(String url, List<String> subdomains)
            throws UrlValidationException, ScanExecutionException {

        log.info("Starting protected scan for URL: {} with circuit breaker", url);

        Supplier<String> decoratedSupplier = CircuitBreaker.decorateSupplier(
                scanCircuitBreaker,
                () -> {
                    try {
                        return self.startScan(url, subdomains);
                    } catch (UrlValidationException | ScanExecutionException e) {
                        throw new RuntimeException(e);
                    }
                }
        );

        try {
            String result = decoratedSupplier.get();
            log.info("Protected scan completed successfully for URL: {}", url);
            return result;

        } catch (CallNotPermittedException e) {
            log.error("Circuit breaker is OPEN - scan service unavailable for URL: {}", url);
            throw new ScanExecutionException(
                    "Scan service is temporarily unavailable due to high error rate. Please try again later."
            );

        } catch (RuntimeException e) {
            // Unwrap the original exception
            if (e.getCause() instanceof UrlValidationException) {
                throw (UrlValidationException) e.getCause();
            } else if (e.getCause() instanceof ScanExecutionException) {
                throw (ScanExecutionException) e.getCause();
            } else {
                log.error("Unexpected error in protected scan for URL: {}", url, e);
                throw new ScanExecutionException("Unexpected error during scan: " + e.getMessage());
            }
        }
    }

    /**
     * Async method to check circuit breaker health
     */
    @Async
    public CompletableFuture<CircuitBreakerHealthInfo> getCircuitBreakerHealth() {
        CircuitBreaker.State state = scanCircuitBreaker.getState();
        CircuitBreaker.Metrics metrics = scanCircuitBreaker.getMetrics();

        CircuitBreakerHealthInfo health = new CircuitBreakerHealthInfo(
                state.toString(),
                metrics.getFailureRate(),
                metrics.getNumberOfSuccessfulCalls(),
                metrics.getNumberOfFailedCalls(),
                metrics.getNumberOfSlowCalls()
        );

        return CompletableFuture.completedFuture(health);
    }

    /**
     * Health information for circuit breaker
     */
    public static class CircuitBreakerHealthInfo {
        private final String state;
        private final float failureRate;
        private final long successfulCalls;
        private final long failedCalls;
        private final long slowCalls;

        public CircuitBreakerHealthInfo(String state, float failureRate, long successfulCalls,
                                        long failedCalls, long slowCalls) {
            this.state = state;
            this.failureRate = failureRate;
            this.successfulCalls = successfulCalls;
            this.failedCalls = failedCalls;
            this.slowCalls = slowCalls;
        }

        // Getters
        public String getState() { return state; }
        public float getFailureRate() { return failureRate; }
        public long getSuccessfulCalls() { return successfulCalls; }
        public long getFailedCalls() { return failedCalls; }
        public long getSlowCalls() { return slowCalls; }

        public boolean isHealthy() {
            return "CLOSED".equals(state) && failureRate < 25.0f;
        }
    }

    public String startScan(String url, List<String> subdomains) throws UrlValidationException, ScanExecutionException {
        log.info("Received request to scan URL: {} with {} subdomains", url, subdomains != null ? subdomains.size() : 0);

        try {
            ValidationResult validationResult = UrlAndCookieUtil.validateUrlForScanning(url);
            if (!validationResult.isValid()) {
                throw new UrlValidationException(ErrorCodes.VALIDATION_ERROR,
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
                    throw new UrlValidationException(ErrorCodes.VALIDATION_ERROR,
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
                    .setTimeout(30000)
                    .setSlowMo(0);

            browser = playwright.chromium().launch(launchOptions);

            Browser.NewContextOptions contextOptions = new Browser.NewContextOptions()
                    .setUserAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                    .setViewportSize(1920, 1080)
                    .setIgnoreHTTPSErrors(false)
                    .setJavaScriptEnabled(true)
                    .setAcceptDownloads(false)
                    .setPermissions(Arrays.asList("geolocation", "notifications"));

            context = browser.newContext(contextOptions);
            context.setDefaultTimeout(15000);
            context.setDefaultNavigationTimeout(15000);

            context.onRequest(request -> {
                processedUrls.add(request.url());
            });

            Page page = context.newPage();

            scanMetrics.setScanPhase("LOADING_PAGE");
            log.info("=== PHASE 1: Loading main page with extended wait ===");
            Response response = null;
            try {
                response = page.navigate(url, new Page.NavigateOptions()
                        .setWaitUntil(WaitUntilState.NETWORKIDLE)
                        .setTimeout(20000));
            } catch (TimeoutError e) {
                log.warn("Networkidle timeout, trying with domcontentloaded for {}", url);
                try {
                    response = page.navigate(url, new Page.NavigateOptions()
                            .setWaitUntil(WaitUntilState.DOMCONTENTLOADED)
                            .setTimeout(15000));
                } catch (TimeoutError e2) {
                    log.warn("Domcontentloaded timeout, trying basic load for {}", url);
                    response = page.navigate(url, new Page.NavigateOptions()
                            .setWaitUntil(WaitUntilState.LOAD)
                            .setTimeout(10000));
                }
            }
            if (response == null || !response.ok()) {
                throw new ScanExecutionException("Failed to load page. Status: " + (response != null ? response.status() : "No response"));
            }

            page.waitForTimeout(500);

            for (int pass = 1; pass <= 3; pass++) {
                log.info("Cookie capture pass {} of 3", pass);
                captureBrowserCookiesEnhanced(context, url, discoveredCookies, transactionId, scanMetrics);

                if (pass < 3) {
                    page.waitForTimeout(1000);
                }
            }

            if ((Boolean) page.evaluate("document.querySelectorAll('iframe, embed, object').length > 0")) {
                log.info("Embedded content detected - extending wait time");
                page.waitForTimeout(1500);
            }

            captureBrowserCookiesEnhanced(context, url, discoveredCookies, transactionId, scanMetrics);

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

            page.waitForTimeout(1500);
            captureBrowserCookiesEnhanced(context, url, discoveredCookies, transactionId, scanMetrics);

            page.waitForTimeout(2500);
            captureBrowserCookiesEnhanced(context, url, discoveredCookies, transactionId, scanMetrics);
            triggerPatternBasedCookieDiscovery(page, context, url, discoveredCookies, transactionId, scanMetrics);
            detectGenericAuthenticationFlow(page, context, url, discoveredCookies, transactionId, scanMetrics);
            detectGenericPixelTracking(page, context, url, discoveredCookies, transactionId, scanMetrics);
            detectGenericApplicationState(page, context, url, discoveredCookies, transactionId, scanMetrics);

            captureBrowserCookiesEnhanced(context, url, discoveredCookies, transactionId, scanMetrics);
            // === PHASE 3: AGGRESSIVE CONSENT HANDLING ===
            scanMetrics.setScanPhase("HANDLING_CONSENT");
            log.info("=== PHASE 3: Aggressive consent banner handling ===");

            boolean consentHandled = CookieDetectionUtil.handleConsentBanners(page, 8000);

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
                page.waitForTimeout(2500);
                captureBrowserCookiesEnhanced(context, url, discoveredCookies, transactionId, scanMetrics);
                log.info("Captured storage after consent handling");
            }

            // === NEW: GENERIC AUTHENTICATION FLOW DETECTION ===
            scanMetrics.setScanPhase("AUTHENTICATION_DETECTION");
            log.info("=== PHASE 3.5: Generic authentication flow detection ===");
            detectAndTriggerAuthenticationFlows(page, url, transactionId, discoveredCookies, scanMetrics);
            log.info("Captured storage after authentication triggers");

            // === NEW: SOCIAL WIDGET DETECTION ===
            scanMetrics.setScanPhase("SOCIAL_WIDGETS");
            log.info("=== PHASE 3.6: Social widget detection ===");
            detectAndTriggerSocialWidgets(page, url, transactionId, discoveredCookies, scanMetrics);
            log.info("Captured storage after social widget detection");

            // === PHASE 4: AGGRESSIVE USER SIMULATION ===
            scanMetrics.setScanPhase("USER_INTERACTIONS");

            log.info("=== PHASE 4: Aggressive user interaction simulation ===");
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
            }

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

            for (int i = 0; i < 4; i++) {
                int x = 200 + (i * 100);
                int y = 200 + (i * 50);
                page.mouse().move(x, y);
                page.waitForTimeout(200);
            }

            page.waitForTimeout(2500); // Longer wait after interactions
            captureBrowserCookiesEnhanced(context, url, discoveredCookies, transactionId, scanMetrics);
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

            page.waitForTimeout(2500);
            captureBrowserCookiesEnhanced(context, url, discoveredCookies, transactionId, scanMetrics);
            log.info("Captured storage after user interactions");

            // Add this right after the analytics event triggering section
            scanMetrics.setScanPhase("COOKIE_SYNC_DETECTION");
            log.info("=== PHASE 6: Cookie synchronization detection ===");

            page.evaluate("""
    // Cookie synchronization detection
    const syncEndpoints = [
        '/sync', '/cookie-sync', '/cm', '/match', '/usersync',
        '/pixel/sync', '/rtb/sync', '/dsp/sync'
    ];
    
    // Common sync parameters
    const syncParams = [
        'gdpr=1&gdpr_consent=dummy',
        'redir=' + encodeURIComponent(window.location.href),
        'partner_uid=dummy',
        'sync_type=iframe'
    ];
    
    // Trigger cookie sync for discovered domains
    const domains = Array.from(document.querySelectorAll('[src*="//"], [href*="//"]'))
        .map(el => {
            try {
                const url = el.src || el.href;
                return new URL(url).origin;
            } catch(e) { return null; }
        })
        .filter(Boolean)
        .filter((v, i, a) => a.indexOf(v) === i) // unique
        .slice(0, 10);
    
    domains.forEach(domain => {
        syncEndpoints.forEach(endpoint => {
            syncParams.forEach(params => {
                try {
                    const syncUrl = domain + endpoint + '?' + params;
                    
                    // Iframe sync
                    const iframe = document.createElement('iframe');
                    iframe.style.display = 'none';
                    iframe.style.width = '1px';
                    iframe.style.height = '1px';
                    iframe.src = syncUrl;
                    document.body.appendChild(iframe);
                    
                    // Image sync
                    const img = new Image();
                    img.style.display = 'none';
                    img.src = syncUrl;
                    document.body.appendChild(img);
                    
                } catch(e) {}
            });
        });
    });
""");

            page.waitForTimeout(4000);
            captureBrowserCookiesEnhanced(context, url, discoveredCookies, transactionId, scanMetrics);

            scanMetrics.setScanPhase("CROSS_DOMAIN_REQUESTS");
            log.info("=== PHASE 6: Cross-domain and subdomain requests ===");

            String rootDomain = UrlAndCookieUtil.extractRootDomain(url);

            handleIframesAndEmbeds(page, url, transactionId, discoveredCookies, scanMetrics);

            if (subdomains != null && !subdomains.isEmpty()) {
                int maxSubdomainsToScan = subdomains.size();

                scanMetrics.setScanPhase("SCANNING_SUBDOMAINS");

                for (int i = 0; i < maxSubdomainsToScan -1; i++) {
                    String subdomain = subdomains.get(i);
                    String subdomainName = SubdomainValidationUtil.extractSubdomainName(subdomain, rootDomain);

                    log.info("=== ESSENTIAL SUBDOMAIN {}/{}: {} (Name: {}) ===",
                            i+1, maxSubdomainsToScan, subdomain, subdomainName);

                    try {
                        Response subdomainResponse = page.navigate(subdomain, new Page.NavigateOptions()
                                .setWaitUntil(WaitUntilState.DOMCONTENTLOADED) // FASTER
                                .setTimeout(8000)); // SHORTER timeout

                        if (subdomainResponse != null && subdomainResponse.ok()) {
                            page.waitForTimeout(1500);

                            captureBrowserCookiesWithSubdomainName(context, subdomain, discoveredCookies,
                                    transactionId, scanMetrics, subdomainName);

                            try {
                                boolean subdomainConsentHandled = CookieDetectionUtil.handleConsentBanners(page, 5000); // SHORTER
                                if (subdomainConsentHandled) {
                                    page.waitForTimeout(1000);
                                    captureBrowserCookiesWithSubdomainName(context, subdomain, discoveredCookies,
                                            transactionId, scanMetrics, subdomainName);
                                    log.info("Captured cookies after consent on subdomain: {}", subdomainName);
                                }
                            } catch (Exception e) {
                                log.debug("Subdomain consent handling failed for {}: {}", subdomain, e.getMessage());
                            }

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
            int[] waitTimes = {500, 1500}; // Different wait intervals
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

    private String determineSubdomainNameFromUrl(String currentUrl, String mainUrl, List<String> subdomains) {
        try {
            String rootDomain = UrlAndCookieUtil.extractRootDomain(mainUrl);
            String currentRootDomain = UrlAndCookieUtil.extractRootDomain(currentUrl);

            // Only process cookies from same root domain
            if (!rootDomain.equalsIgnoreCase(currentRootDomain)) {
                return null;
            }

            // If no subdomains provided, everything goes to "main"
            if (subdomains == null || subdomains.isEmpty()) {
                return "main";
            }

            // **KEY LOGIC: Track which domain we're currently scanning**
            // You need to pass the "intended target domain" not the "current redirect URL"
            return determineIntendedSubdomain(mainUrl, subdomains);

        } catch (Exception e) {
            log.debug("Error determining subdomain name for {}: {}", currentUrl, e.getMessage());
            return "main";
        }
    }

    private String determineIntendedSubdomain(String targetUrl, List<String> subdomains) {
        try {
            String rootDomain = UrlAndCookieUtil.extractRootDomain(targetUrl);

            if (subdomains != null) {
                for (String providedSubdomain : subdomains) {
                    String providedSubdomainName = SubdomainValidationUtil.extractSubdomainName(providedSubdomain, rootDomain);
                    String targetSubdomainName = SubdomainValidationUtil.extractSubdomainName(targetUrl, rootDomain);

                    if (providedSubdomainName.equals(targetSubdomainName)) {
                        return providedSubdomainName;
                    }
                }
            }

            return "main";
        } catch (Exception e) {
            return "main";
        }
    }


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

                    // **FORCE "main" if no subdomains provided**
                    cookieDto.setSubdomainName("main");

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

            page.waitForTimeout(1500);

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
                                new Frame.WaitForLoadStateOptions().setTimeout(5000));
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
                        log.debug("Frame js error timeout: {}", frameUrl);
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

            interactWithAllIframes(page, iframes);
            page.waitForTimeout(1500);
            captureBrowserCookiesEnhanced(page.context(), url, discoveredCookies, transactionId, scanMetrics);

            triggerGenericIframeEvents(page);
            page.waitForTimeout(1000);
            captureBrowserCookiesEnhanced(page.context(), url, discoveredCookies, transactionId, scanMetrics);

            loadCommonEmbedPatterns(page);
            page.waitForTimeout(1500);
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

    private void detectGenericAuthenticationFlow(Page page, BrowserContext context, String mainUrl,
                                                 Map<String, CookieDto> discoveredCookies,
                                                 String transactionId, ScanPerformanceTracker.ScanMetrics scanMetrics) {
        try {
            log.info("=== GENERIC AUTHENTICATION FLOW DETECTION ===");

            String hostname = new URL(mainUrl).getHost();
            String rootDomain = UrlAndCookieUtil.extractRootDomain(mainUrl);
            String protocol = mainUrl.startsWith("https") ? "https" : "http";

            // Generic authentication subdomain patterns
            List<String> authSubdomains = Arrays.asList(
                    "login", "auth", "accounts", "oauth", "sso", "signin", "id", "identity",
                    "connect", "authorize", "authentication", "portal", "secure"
            );

            // Generic authentication path patterns
            List<String> authPaths = Arrays.asList(
                    "/oauth/authorize", "/oauth2/authorize", "/auth/login", "/sso/login",
                    "/login/oauth/authorize", "/oauth2/v1/authorize", "/oauth2/v2/authorize",
                    "/connect/authorize", "/identity/connect/authorize", "/openid_connect/authorize",
                    "/auth", "/login", "/signin", "/sso", "/authenticate"
            );

            // Build authentication URLs
            List<String> authUrls = new ArrayList<>();

            // Add subdomain + path combinations
            for (String subdomain : authSubdomains) {
                for (String path : authPaths) {
                    authUrls.add(protocol + "://" + subdomain + "." + rootDomain + path);
                }
            }

            // Add main domain auth paths
            for (String path : authPaths) {
                authUrls.add(protocol + "://" + hostname + path);
            }

            // Discover auth URLs from page content
            List<String> discoveredAuthUrls = (List<String>) page.evaluate("""
            Array.from(document.querySelectorAll('a[href], form[action]'))
                .map(el => el.href || el.action)
                .filter(url => url && (
                    url.includes('oauth') || url.includes('auth') || 
                    url.includes('login') || url.includes('sso') ||
                    url.includes('signin') || url.includes('connect') ||
                    url.includes('authorize')
                ))
                .slice(0, 15);
        """);

            authUrls.addAll(discoveredAuthUrls);

            // Visit authentication endpoints (limit to prevent timeout)
            for (int i = 0; i < Math.min(authUrls.size(), 20); i++) {
                String authUrl = authUrls.get(i);
                try {
                    log.debug("Visiting auth endpoint: {}", authUrl);

                    // Set generic authentication headers
                    Map<String, String> headers = Map.of(
                            "Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8",
                            "Accept-Language", "en-US,en;q=0.5",
                            "Referer", mainUrl,
                            "DNT", "1"
                    );

                    page.setExtraHTTPHeaders(headers);
                    page.navigate(authUrl, new Page.NavigateOptions().setTimeout(4000));
                    page.waitForTimeout(1000);

                    captureBrowserCookiesEnhanced(context, authUrl, discoveredCookies, transactionId, scanMetrics);

                } catch (Exception e) {
                    log.debug("Auth endpoint failed: {} - {}", authUrl, e.getMessage());
                }
            }

            // Trigger generic authentication pixel tracking
            page.evaluate("""
            // Generic authentication tracking endpoints
            const authPixels = [
                '/auth/track', '/login/track', '/sso/track', '/oauth/track',
                '/auth/pixel', '/login/pixel', '/sso/pixel', '/oauth/pixel',
                '/c.gif?auth=1', '/px.gif?login=1', '/track?event=auth'
            ];
            
            const currentOrigin = window.location.origin;
            const trackingParams = [
                'event=auth_attempt&t=' + Date.now(),
                'action=login&ts=' + Date.now(),
                'auth_flow=oauth&rnd=' + Math.random(),
                't=' + Date.now() + '&ref=' + encodeURIComponent(document.location.href)
            ];
            
            authPixels.forEach(pixel => {
                trackingParams.forEach(params => {
                    try {
                        const img = new Image();
                        img.style.display = 'none';
                        img.style.width = '1px';
                        img.style.height = '1px';
                        img.src = currentOrigin + pixel + '?' + params;
                        document.body.appendChild(img);
                    } catch(e) {}
                });
            });
        """);

            page.waitForTimeout(1500);
            captureBrowserCookiesEnhanced(context, mainUrl, discoveredCookies, transactionId, scanMetrics);

        } catch (Exception e) {
            log.warn("Error in generic authentication flow: {}", e.getMessage());
        }
    }

    private void detectGenericPixelTracking(Page page, BrowserContext context, String mainUrl,
                                            Map<String, CookieDto> discoveredCookies,
                                            String transactionId, ScanPerformanceTracker.ScanMetrics scanMetrics) {
        try {
            log.info("=== GENERIC PIXEL TRACKING DETECTION ===");

            page.evaluate("""
            // Generic pixel tracking endpoints
            const pixelEndpoints = [
                '/c.gif', '/px.gif', '/pixel.gif', '/track.gif', '/beacon.gif',
                '/p.gif', '/b.gif', '/i.gif', '/1x1.gif', '/pixel.png',
                '/collect', '/analytics', '/track', '/hit', '/event', '/log',
                '/tr', '/pixel', '/beacon', '/impression', '/conversion',
                '/sync', '/rtb', '/dsp', '/ssp', '/cm', '/match'
            ];
            
            // Generic tracking parameters
            const baseParams = {
                timestamp: Date.now(),
                random: Math.random(),
                session: Math.random().toString(36).substr(2, 16),
                user: Math.random().toString(36).substr(2, 12),
                page: encodeURIComponent(document.location.href),
                referrer: encodeURIComponent(document.referrer || ''),
                title: encodeURIComponent(document.title || '')
            };
            
            const paramVariations = [
                't=' + baseParams.timestamp,
                'rnd=' + baseParams.random,
                'ts=' + baseParams.timestamp + '&r=' + baseParams.random,
                'pid=' + baseParams.user + '&sid=' + baseParams.session,
                'url=' + baseParams.page,
                'ref=' + baseParams.referrer,
                'title=' + baseParams.title,
                'event=pageview&t=' + baseParams.timestamp,
                'action=page_view&ts=' + baseParams.timestamp,
                'v=1&t=pageview&tid=dummy&cid=' + baseParams.user,
                'data=' + encodeURIComponent('{"event":"pageview","timestamp":' + baseParams.timestamp + '}')
            ];
            
            // Get all unique external domains from page
            const allDomains = new Set();
            
            // From various elements
            const selectors = [
                'script[src]', 'img[src]', 'iframe[src]', 'link[href]',
                'embed[src]', 'object[data]', 'video[src]', 'audio[src]'
            ];
            
            selectors.forEach(selector => {
                Array.from(document.querySelectorAll(selector)).forEach(el => {
                    try {
                        const url = el.src || el.href || el.data;
                        if (url && url.startsWith('http')) {
                            const hostname = new URL(url).hostname;
                            if (hostname !== window.location.hostname) {
                                allDomains.add('https://' + hostname);
                                // Also try common subdomains
                                const rootDomain = hostname.split('.').slice(-2).join('.');
                                ['analytics', 'track', 'pixel', 'api', 'cdn', 'static', 'www'].forEach(sub => {
                                    allDomains.add('https://' + sub + '.' + rootDomain);
                                });
                            }
                        }
                    } catch(e) {}
                });
            });
            
            // Include current domain
            allDomains.add(window.location.origin);
            
            // Limit to prevent performance issues
            const domains = Array.from(allDomains).slice(0, 25);
            
            console.log('Triggering generic pixels on', domains.length, 'domains');
            
            // Trigger pixel requests with multiple methods
            domains.forEach(domain => {
                pixelEndpoints.forEach(endpoint => {
                    paramVariations.forEach(params => {
                        const fullUrl = domain + endpoint + '?' + params;
                        
                        try {
                            // Method 1: Image pixel (most common)
                            const img = new Image();
                            img.style.display = 'none';
                            img.style.width = '1px';
                            img.style.height = '1px';
                            img.src = fullUrl;
                            img.onload = () => console.log('Pixel loaded:', endpoint);
                            document.body.appendChild(img);
                            
                            // Method 2: Fetch with credentials
                            fetch(fullUrl, {
                                method: 'GET',
                                mode: 'no-cors',
                                credentials: 'include',
                                cache: 'no-cache'
                            }).catch(() => {});
                            
                            // Method 3: JSONP-style script (for some tracking systems)
                            if (Math.random() < 0.3) { // Only for some requests to avoid overload
                                const script = document.createElement('script');
                                script.src = fullUrl + '&callback=dummy' + Date.now();
                                script.onerror = () => {};
                                script.onload = () => script.remove();
                                document.head.appendChild(script);
                                setTimeout(() => script.remove(), 2000);
                            }
                            
                        } catch(e) {
                            console.debug('Pixel error for', endpoint, ':', e.message);
                        }
                    });
                });
            });
        """);

            page.waitForTimeout(2000);
            captureBrowserCookiesEnhanced(context, mainUrl, discoveredCookies, transactionId, scanMetrics);

        } catch (Exception e) {
            log.warn("Error in generic pixel detection: {}", e.getMessage());
        }
    }

    private void detectGenericApplicationState(Page page, BrowserContext context, String mainUrl,
                                               Map<String, CookieDto> discoveredCookies,
                                               String transactionId, ScanPerformanceTracker.ScanMetrics scanMetrics) {
        try {
            log.info("=== GENERIC APPLICATION STATE DETECTION ===");

            // Trigger application-specific interactions that commonly set state cookies
            page.evaluate("""
            console.log('Starting generic application state detection...');
            
            // 1. Location and geolocation triggers
            if (navigator.geolocation) {
                navigator.geolocation.getCurrentPosition(() => {}, () => {}, {
                    timeout: 1000,
                    enableHighAccuracy: false
                });
            }
            
            // Trigger location permission events
            window.dispatchEvent(new Event('geolocationchange'));
            
            // 2. Generic widget and component interactions
            const interactiveSelectors = [
                // Weather widgets
                '[class*="weather"]', '[id*="weather"]', '[data-weather]',
                // Location widgets  
                '[class*="location"]', '[id*="location"]', '[data-location]',
                // Authentication elements
                '[class*="auth"]', '[id*="auth"]', '[data-auth]',
                '[class*="login"]', '[id*="login"]', '[data-login]',
                '[class*="account"]', '[id*="account"]', '[data-account]',
                // Personalization elements
                '[class*="personal"]', '[id*="personal"]', '[data-personal]',
                '[class*="preference"]', '[id*="preference"]', '[data-preference]',
                // Settings and configuration
                '[class*="setting"]', '[id*="setting"]', '[data-setting]',
                '[class*="config"]', '[id*="config"]', '[data-config]',
                // User state elements
                '[class*="user"]', '[id*="user"]', '[data-user]',
                '[class*="profile"]', '[id*="profile"]', '[data-profile]'
            ];
            
            interactiveSelectors.forEach(selector => {
                try {
                    const elements = document.querySelectorAll(selector);
                    Array.from(elements).slice(0, 5).forEach(el => {
                        try {
                            // Multiple interaction types to trigger state changes
                            ['mouseover', 'mouseenter', 'focus', 'click', 'change'].forEach(event => {
                                el.dispatchEvent(new MouseEvent(event, {
                                    bubbles: true, 
                                    cancelable: true, 
                                    view: window
                                }));
                            });
                            
                            // Scroll element into view (triggers lazy loading)
                            el.scrollIntoView({behavior: 'smooth', block: 'center'});
                            
                        } catch(e) {
                            console.debug('Element interaction error:', e.message);
                        }
                    });
                } catch(e) {
                    console.debug('Selector error for', selector, ':', e.message);
                }
            });
            
            // 3. Generic browser and application events
            const appEvents = [
                'resize', 'orientationchange', 'visibilitychange', 
                'focus', 'blur', 'online', 'offline',
                'beforeunload', 'pagehide', 'pageshow',
                'hashchange', 'popstate', 'storage'
            ];
            
            appEvents.forEach(eventType => {
                try {
                    window.dispatchEvent(new Event(eventType, {bubbles: true}));
                } catch(e) {
                    console.debug('Event dispatch error for', eventType, ':', e.message);
                }
            });
            
            // 4. Generic localStorage/sessionStorage interactions (trigger storage events)
            const storageKeys = [
                'user_preferences', 'app_state', 'session_data', 
                'user_settings', 'location_data', 'theme_preference',
                'language_preference', 'timezone', 'last_visit'
            ];
            
            storageKeys.forEach(key => {
                try {
                    // Set and remove to trigger storage events
                    const testValue = 'test_' + Date.now();
                    localStorage.setItem(key, testValue);
                    window.dispatchEvent(new StorageEvent('storage', {
                        key: key,
                        newValue: testValue,
                        oldValue: null
                    }));
                    
                    // Clean up
                    setTimeout(() => {
                        try { localStorage.removeItem(key); } catch(e) {}
                    }, 100);
                    
                } catch(e) {
                    console.debug('Storage interaction error for', key, ':', e.message);
                }
            });
            
            // 5. Generic form and input interactions
            const formElements = document.querySelectorAll('input, select, textarea, form');
            Array.from(formElements).slice(0, 10).forEach(element => {
                try {
                    ['focus', 'blur', 'change', 'input'].forEach(eventType => {
                        element.dispatchEvent(new Event(eventType, {bubbles: true}));
                    });
                } catch(e) {}
            });
            
            // 6. Generic media and content interactions
            const mediaElements = document.querySelectorAll('video, audio, iframe, embed');
            Array.from(mediaElements).forEach(element => {
                try {
                    ['loadstart', 'loadeddata', 'canplay', 'play', 'pause'].forEach(eventType => {
                        element.dispatchEvent(new Event(eventType, {bubbles: true}));
                    });
                } catch(e) {}
            });
            
            console.log('Generic application state interactions completed');
        """);

            page.waitForTimeout(1500);
            captureBrowserCookiesEnhanced(context, mainUrl, discoveredCookies, transactionId, scanMetrics);

        } catch (Exception e) {
            log.warn("Error in generic application state detection: {}", e.getMessage());
        }
    }

    private void triggerPatternBasedCookieDiscovery(Page page, BrowserContext context, String mainUrl,
                                                    Map<String, CookieDto> discoveredCookies,
                                                    String transactionId, ScanPerformanceTracker.ScanMetrics scanMetrics) {
        try {
            log.info("=== PATTERN-BASED COOKIE DISCOVERY ===");

            // FIX: Wrap the code in a function
            Map<String, String> domainPatterns = (Map<String, String>) page.evaluate("""
            (function() {
                const domains = {};
                
                // Analyze all external resources
                Array.from(document.querySelectorAll('[src], [href], [action]')).forEach(el => {
                    try {
                        const url = el.src || el.href || el.action;
                        if (url && url.startsWith('http')) {
                            const hostname = new URL(url).hostname.toLowerCase();
                            if (hostname !== window.location.hostname) {
                                
                                // Classify domain purpose based on URL patterns
                                let purpose = 'unknown';
                                if (url.includes('analytics') || url.includes('track') || url.includes('collect')) {
                                    purpose = 'analytics';
                                } else if (url.includes('ads') || url.includes('advertising') || url.includes('doubleclick')) {
                                    purpose = 'advertising';
                                } else if (url.includes('auth') || url.includes('login') || url.includes('oauth')) {
                                    purpose = 'authentication';
                                } else if (url.includes('social') || url.includes('share') || url.includes('widget')) {
                                    purpose = 'social';
                                } else if (url.includes('cdn') || url.includes('static') || url.includes('assets')) {
                                    purpose = 'cdn';
                                }
                                
                                domains[hostname] = purpose;
                            }
                        }
                    } catch(e) {}
                });
                
                return domains;
            })();
        """);

            // Rest of your method...
        } catch (Exception e) {
            log.warn("Error in pattern-based discovery: {}", e.getMessage());
        }
    }

    private List<String> getEndpointsForPurpose(String purpose) {
        switch (purpose) {
            case "analytics":
                return Arrays.asList("/collect", "/track", "/analytics", "/hit", "/beacon");
            case "advertising":
                return Arrays.asList("/ads/conversion", "/pixel", "/impression", "/click");
            case "authentication":
                return Arrays.asList("/oauth/authorize", "/login", "/auth", "/sso");
            case "social":
                return Arrays.asList("/share", "/like", "/follow", "/widget");
            default:
                return Arrays.asList("/track", "/pixel", "/c.gif", "/beacon");
        }
    }



    public void performDynamicDiscovery(Page page, BrowserContext context, String mainUrl,
                                        List<String> providedSubdomains,
                                        Map<String, CookieDto> discoveredCookies,
                                        String transactionId,
                                        ScanPerformanceTracker.ScanMetrics scanMetrics) {

        // 1. Discover ALL resources dynamically from the actual page
        ResourceDiscoveryResult resources = discoverAllResources(page);
        log.info("Dynamically discovered {} external domains from page analysis",
                resources.externalDomains.size());

        // 2. Test cookie endpoints on ALL discovered domains (not just known ones)
        testDiscoveredDomainsForCookies(page, context, resources.externalDomains,
                mainUrl, providedSubdomains, discoveredCookies,
                transactionId, scanMetrics);

        // 3. Follow actual redirect chains found on the page
        followDiscoveredRedirects(page, context, resources.redirectChains,
                mainUrl, providedSubdomains, discoveredCookies,
                transactionId, scanMetrics);

        // 4. Test dynamically discovered tracking patterns
        testDynamicTrackingPatterns(page, context, resources.trackingPatterns,
                mainUrl, providedSubdomains, discoveredCookies,
                transactionId, scanMetrics);
    }

    /**
     * Discover all resources from the actual page content - no assumptions
     */
    @SuppressWarnings("unchecked")
    private ResourceDiscoveryResult discoverAllResources(Page page) {
        try {
            Map<String, Object> discoveryResult = (Map<String, Object>) page.evaluate("""
                const result = {
                    externalDomains: [],
                    redirectChains: [],
                    trackingPatterns: [],
                    authEndpoints: [],
                    pixelUrls: []
                };
                
                const currentHostname = window.location.hostname;
                const allUrls = new Set();
                
                // 1. Collect ALL URLs from page elements
                const urlSources = [
                    'script[src]', 'img[src]', 'iframe[src]', 'link[href]',
                    'embed[src]', 'object[data]', 'video[src]', 'audio[src]',
                    'form[action]', 'a[href]', 'area[href]'
                ];
                
                urlSources.forEach(selector => {
                    document.querySelectorAll(selector).forEach(el => {
                        const url = el.src || el.href || el.action || el.data;
                        if (url && (url.startsWith('http') || url.startsWith('https'))) {
                            allUrls.add(url);
                        }
                    });
                });
                
                // 2. Extract URLs from JavaScript code
                const scripts = Array.from(document.querySelectorAll('script')).map(s => s.textContent || '');
                const jsContent = scripts.join(' ');
                const urlMatches = jsContent.match(/https?:\\/\\/[^\\s'"`;,)]+/g) || [];
                urlMatches.forEach(url => {
                    try {
                        // Clean up the URL
                        const cleanUrl = url.replace(/['"`;,)]+$/, '');
                        if (cleanUrl.length > 10) {
                            allUrls.add(cleanUrl);
                        }
                    } catch(e) {}
                });
                
                // 3. Analyze each URL for patterns
                allUrls.forEach(url => {
                    try {
                        const hostname = new URL(url).hostname;
                        const pathname = new URL(url).pathname;
                        const search = new URL(url).search;
                        
                        // Collect external domains
                        if (hostname !== currentHostname) {
                            result.externalDomains.push(hostname);
                        }
                        
                        // Detect tracking patterns dynamically
                        if (pathname.includes('track') || pathname.includes('pixel') || 
                            pathname.includes('beacon') || pathname.includes('collect') ||
                            pathname.includes('analytics') || pathname.endsWith('.gif') ||
                            pathname.endsWith('.png') || search.includes('track') ||
                            search.includes('event') || search.includes('analytics')) {
                            result.trackingPatterns.push(url);
                        }
                        
                        // Detect authentication endpoints dynamically
                        if (pathname.includes('auth') || pathname.includes('login') ||
                            pathname.includes('oauth') || pathname.includes('sso') ||
                            pathname.includes('signin') || search.includes('auth') ||
                            search.includes('login')) {
                            result.authEndpoints.push(url);
                        }
                        
                        // Detect pixel URLs
                        if (pathname.endsWith('.gif') || pathname.endsWith('.png') ||
                            pathname.includes('pixel') || pathname.includes('/c.gif') ||
                            pathname.includes('/px.') || pathname.includes('/tr') ||
                            (pathname.length === 1 && pathname.match(/[a-z]/)) ||
                            search.includes('pixel') || url.includes('1x1')) {
                            result.pixelUrls.push(url);
                        }
                        
                        // Detect redirect patterns
                        if (search.includes('redirect') || search.includes('return_to') ||
                            search.includes('next=') || search.includes('continue=') ||
                            pathname.includes('/redirect') || pathname.includes('/r/') ||
                            pathname.includes('/go/')) {
                            result.redirectChains.push(url);
                        }
                        
                    } catch(e) {
                        // Invalid URL, skip
                    }
                });
                
                // Remove duplicates and limit results
                result.externalDomains = [...new Set(result.externalDomains)].slice(0, 50);
                result.trackingPatterns = [...new Set(result.trackingPatterns)].slice(0, 30);
                result.authEndpoints = [...new Set(result.authEndpoints)].slice(0, 20);
                result.pixelUrls = [...new Set(result.pixelUrls)].slice(0, 40);
                result.redirectChains = [...new Set(result.redirectChains)].slice(0, 15);
                
                return result;
            """);

            return new ResourceDiscoveryResult(
                    (List<String>) discoveryResult.get("externalDomains"),
                    (List<String>) discoveryResult.get("redirectChains"),
                    (List<String>) discoveryResult.get("trackingPatterns"),
                    (List<String>) discoveryResult.get("authEndpoints"),
                    (List<String>) discoveryResult.get("pixelUrls")
            );

        } catch (Exception e) {
            log.warn("Error in dynamic resource discovery: {}", e.getMessage());
            return new ResourceDiscoveryResult(new ArrayList<>(), new ArrayList<>(),
                    new ArrayList<>(), new ArrayList<>(), new ArrayList<>());
        }
    }

    /**
     * Test cookie endpoints on dynamically discovered domains
     */
    private void testDiscoveredDomainsForCookies(Page page, BrowserContext context,
                                                 List<String> discoveredDomains,
                                                 String mainUrl, List<String> providedSubdomains,
                                                 Map<String, CookieDto> discoveredCookies,
                                                 String transactionId,
                                                 ScanPerformanceTracker.ScanMetrics scanMetrics) {

        log.info("Testing {} dynamically discovered domains for cookies", discoveredDomains.size());

        // Generate common endpoint patterns dynamically
        List<String> commonEndpoints = Arrays.asList(
                "/", "/track", "/pixel", "/beacon", "/collect", "/analytics",
                "/c.gif", "/px.gif", "/tr", "/hit", "/log", "/event"
        );

        for (String domain : discoveredDomains) {
            for (String endpoint : commonEndpoints) {
                try {
                    String testUrl = "https://" + domain + endpoint;

                    // Quick test navigation
                    page.navigate(testUrl, new Page.NavigateOptions().setTimeout(3000));
                    page.waitForTimeout(500);

                    // Capture any cookies set
                    captureBrowserCookiesFromDiscoveredDomain(context, testUrl, domain,
                            mainUrl, providedSubdomains,
                            discoveredCookies, transactionId, scanMetrics);

                } catch (Exception e) {
                    // Expected to fail for many URLs - continue silently
                    log.debug("Test failed for {}: {}", domain + endpoint, e.getMessage());
                }
            }
        }
    }

    /**
     * Follow actual redirect chains discovered on the page
     */
    private void followDiscoveredRedirects(Page page, BrowserContext context,
                                           List<String> redirectUrls,
                                           String mainUrl, List<String> providedSubdomains,
                                           Map<String, CookieDto> discoveredCookies,
                                           String transactionId,
                                           ScanPerformanceTracker.ScanMetrics scanMetrics) {

        log.info("Following {} discovered redirect chains", redirectUrls.size());

        for (String redirectUrl : redirectUrls) {
            try {
                log.debug("Following redirect: {}", redirectUrl);

                // Navigate and capture cookies at each step
                Response response = page.navigate(redirectUrl, new Page.NavigateOptions().setTimeout(5000));

                if (response != null) {
                    page.waitForTimeout(1000);
                    captureBrowserCookiesFromDiscoveredDomain(context, redirectUrl,
                            new URL(redirectUrl).getHost(),
                            mainUrl, providedSubdomains,
                            discoveredCookies, transactionId, scanMetrics);
                }

            } catch (Exception e) {
                log.debug("Redirect failed for {}: {}", redirectUrl, e.getMessage());
            }
        }
    }

    /**
     * Test dynamically discovered tracking patterns
     */
    private void testDynamicTrackingPatterns(Page page, BrowserContext context,
                                             List<String> trackingUrls,
                                             String mainUrl, List<String> providedSubdomains,
                                             Map<String, CookieDto> discoveredCookies,
                                             String transactionId,
                                             ScanPerformanceTracker.ScanMetrics scanMetrics) {

        log.info("Testing {} discovered tracking patterns", trackingUrls.size());

        // Test each discovered tracking URL
        for (String trackingUrl : trackingUrls) {
            try {
                log.debug("Testing tracking URL: {}", trackingUrl);

                // Add dynamic parameters to trigger more cookie setting
                String enhancedUrl = trackingUrl;
                if (!trackingUrl.contains("?")) {
                    enhancedUrl += "?t=" + System.currentTimeMillis();
                } else {
                    enhancedUrl += "&t=" + System.currentTimeMillis();
                }

                page.navigate(enhancedUrl, new Page.NavigateOptions().setTimeout(4000));
                page.waitForTimeout(800);

                captureBrowserCookiesFromDiscoveredDomain(context, enhancedUrl,
                        new URL(trackingUrl).getHost(),
                        mainUrl, providedSubdomains,
                        discoveredCookies, transactionId, scanMetrics);

            } catch (Exception e) {
                log.debug("Tracking pattern test failed for {}: {}", trackingUrl, e.getMessage());
            }
        }
    }

    private void captureBrowserCookiesFromDiscoveredDomain(BrowserContext context, String sourceUrl,
                                                           String sourceDomain, String mainUrl,
                                                           List<String> providedSubdomains,
                                                           Map<String, CookieDto> discoveredCookies,
                                                           String transactionId,
                                                           ScanPerformanceTracker.ScanMetrics scanMetrics) {
        try {
            List<Cookie> browserCookies = context.cookies();
            String mainRootDomain = UrlAndCookieUtil.extractRootDomain(mainUrl);

            for (Cookie cookie : browserCookies) {
                try {
                    // Determine source type dynamically
                    String cookieRootDomain = UrlAndCookieUtil.extractRootDomain(cookie.domain);
                    Source source = mainRootDomain.equalsIgnoreCase(cookieRootDomain) ?
                            Source.FIRST_PARTY : Source.THIRD_PARTY;

                    CookieDto cookieDto = mapPlaywrightCookie(cookie, sourceUrl, mainRootDomain);
                    cookieDto.setSource(source);

                    // Determine subdomain attribution dynamically
                    String subdomainName = determineSubdomainFromSourceDomain(sourceDomain,
                            mainUrl, providedSubdomains);
                    cookieDto.setSubdomainName(subdomainName);

                    String cookieKey = generateCookieKey(cookie.name, cookie.domain, subdomainName);

                    if (!discoveredCookies.containsKey(cookieKey)) {
                        discoveredCookies.put(cookieKey, cookieDto);
                        saveIncrementalCookieWithFlush(transactionId, cookieDto);
                        scanMetrics.incrementCookiesFound(source.name());

                        log.info("DYNAMIC DISCOVERY: {} from {} (Source: {}, Subdomain: {})",
                                cookie.name, sourceDomain, source, subdomainName);
                    }

                } catch (Exception e) {
                    log.warn("Error processing discovered cookie {}: {}", cookie.name, e.getMessage());
                }
            }
        } catch (Exception e) {
            log.warn("Error capturing cookies from discovered domain {}: {}", sourceDomain, e.getMessage());
        }
    }

    private String determineSubdomainFromSourceDomain(String sourceDomain, String mainUrl,
                                                      List<String> providedSubdomains) {
        try {
            // Check if source domain matches any provided subdomain
            if (providedSubdomains != null) {
                for (String subdomainUrl : providedSubdomains) {
                    try {
                        String subdomainHost = new URL(subdomainUrl).getHost();
                        if (sourceDomain.equalsIgnoreCase(subdomainHost)) {
                            String rootDomain = UrlAndCookieUtil.extractRootDomain(mainUrl);
                            return SubdomainValidationUtil.extractSubdomainName(subdomainUrl, rootDomain);
                        }
                    } catch (Exception e) {
                        // Continue checking
                    }
                }
            }

            // Check if it's from main domain
            String mainHost = new URL(mainUrl).getHost();
            if (sourceDomain.equalsIgnoreCase(mainHost)) {
                return "main";
            }

            // For third-party domains, attribute to main by default
            return "main";

        } catch (Exception e) {
            return "main";
        }
    }

    // Data class for discovery results
    private static class ResourceDiscoveryResult {
        final List<String> externalDomains;
        final List<String> redirectChains;
        final List<String> trackingPatterns;
        final List<String> authEndpoints;
        final List<String> pixelUrls;

        ResourceDiscoveryResult(List<String> externalDomains, List<String> redirectChains,
                                List<String> trackingPatterns, List<String> authEndpoints,
                                List<String> pixelUrls) {
            this.externalDomains = externalDomains;
            this.redirectChains = redirectChains;
            this.trackingPatterns = trackingPatterns;
            this.authEndpoints = authEndpoints;
            this.pixelUrls = pixelUrls;
        }
    }

    private void setupComprehensiveNetworkMonitoring(BrowserContext context, String mainUrl,
                                                     Map<String, CookieDto> discoveredCookies,
                                                     String transactionId,
                                                     ScanPerformanceTracker.ScanMetrics scanMetrics,
                                                     List<String> subdomains) {

        // Track ALL network responses - this is key to catching missed cookies
        context.onResponse(response -> {
            try {
                String responseUrl = response.url();
                Map<String, String> headers = response.headers();

                // Log all cookie-related headers from ANY domain
                for (Map.Entry<String, String> header : headers.entrySet()) {
                    String headerName = header.getKey().toLowerCase();
                    String headerValue = header.getValue();

                    // Capture ALL cookie-setting headers, not just from main domain
                    if (headerName.equals("set-cookie") || headerName.equals("set-cookie2")) {
                        log.info("COOKIE HEADER DETECTED: {} = {} from {}",
                                headerName, headerValue, responseUrl);

                        // Parse cookie regardless of domain
                        List<CookieDto> parsedCookies = parseSetCookieHeader(
                                headerValue, responseUrl, response.url()
                        );

                        for (CookieDto cookie : parsedCookies) {
                            // Determine which subdomain this belongs to
                            String subdomainName = determineTargetSubdomain(responseUrl, mainUrl, subdomains);
                            cookie.setSubdomainName(subdomainName);

                            String cookieKey = generateCookieKey(cookie.getName(),
                                    cookie.getDomain(),
                                    cookie.getSubdomainName());

                            if (!discoveredCookies.containsKey(cookieKey)) {
                                discoveredCookies.put(cookieKey, cookie);
                                saveIncrementalCookieWithFlush(transactionId, cookie);
                                log.info("NETWORK COOKIE CAPTURED: {} from {} (subdomain: {})",
                                        cookie.getName(), responseUrl, subdomainName);
                            }
                        }
                    }

                    // Also capture tracking pixels and beacons
                    if (isTrackingPixelResponse(responseUrl, headers)) {
                        log.info("TRACKING PIXEL DETECTED: {} with headers: {}", responseUrl, headers);
                        // Create synthetic cookie entry for tracking pixel
                        createTrackingPixelCookie(responseUrl, mainUrl, subdomains,
                                discoveredCookies, transactionId);
                    }
                }

            } catch (Exception e) {
                log.warn("Error processing network response: {}", e.getMessage());
            }
        });

        // Monitor requests to detect redirect chains
        context.onRequest(request -> {
            try {
                String requestUrl = request.url();

                // Track all third-party requests that might set cookies
                if (isThirdPartyRequest(requestUrl, mainUrl)) {
                    log.debug("THIRD-PARTY REQUEST: {}", requestUrl);

                    // Some cookies are set via query parameters in redirects
                    extractCookiesFromUrlParameters(requestUrl, mainUrl, subdomains,
                            discoveredCookies, transactionId);
                }

            } catch (Exception e) {
                log.debug("Error processing request: {}", e.getMessage());
            }
        });
    }

    private String determineTargetSubdomain(String responseUrl, String mainUrl, List<String> subdomains) {
        try {
            String responseHost = new URL(responseUrl).getHost().toLowerCase();
            String mainHost = new URL(mainUrl).getHost().toLowerCase();

            // If response is from main domain
            if (responseHost.equals(mainHost)) {
                return "main";
            }

            // Check if response is from one of the provided subdomains
            if (subdomains != null) {
                for (String subdomainUrl : subdomains) {
                    try {
                        String subdomainHost = new URL(subdomainUrl).getHost().toLowerCase();
                        if (responseHost.equals(subdomainHost)) {
                            String rootDomain = UrlAndCookieUtil.extractRootDomain(mainUrl);
                            return SubdomainValidationUtil.extractSubdomainName(subdomainUrl, rootDomain);
                        }
                    } catch (Exception e) {
                        // Continue checking other subdomains
                    }
                }
            }

            // If it's a third-party domain, check if it's related to any of our subdomains
            String rootDomain = UrlAndCookieUtil.extractRootDomain(mainUrl);
            String responseRootDomain = UrlAndCookieUtil.extractRootDomain(responseUrl);

            if (rootDomain.equalsIgnoreCase(responseRootDomain)) {
                // It's a subdomain we weren't explicitly told about, but same root domain
                return SubdomainValidationUtil.extractSubdomainName(responseUrl, rootDomain);
            }

            // Third-party cookie - attribute to main for now
            return "main";

        } catch (Exception e) {
            return "main";
        }
    }

    private boolean isTrackingPixelResponse(String url, Map<String, String> headers) {
        String lowerUrl = url.toLowerCase();

        // Check for tracking pixel characteristics
        boolean hasPixelUrl = lowerUrl.contains(".gif") || lowerUrl.contains(".png") ||
                lowerUrl.contains("pixel") || lowerUrl.contains("track") ||
                lowerUrl.contains("beacon") || lowerUrl.contains("collect");

        // Check response headers
        String contentType = headers.getOrDefault("content-type", "").toLowerCase();
        boolean hasPixelContentType = contentType.contains("image/") ||
                contentType.contains("application/json") ||
                contentType.contains("text/plain");

        // Check for 1x1 pixel responses
        String contentLength = headers.getOrDefault("content-length", "0");
        boolean isSmallResponse = contentLength.equals("43") || contentLength.equals("35") ||
                contentLength.equals("807"); // Common pixel sizes

        return hasPixelUrl || (hasPixelContentType && isSmallResponse);
    }

    private void createTrackingPixelCookie(String pixelUrl, String mainUrl, List<String> subdomains,
                                           Map<String, CookieDto> discoveredCookies, String transactionId) {
        try {
            // Create a synthetic cookie entry for tracking pixels
            String pixelDomain = new URL(pixelUrl).getHost();
            String cookieName = "tracking_pixel_" + Math.abs(pixelUrl.hashCode());

            CookieDto pixelCookie = new CookieDto(
                    cookieName, pixelUrl, pixelDomain, "/", null,
                    false, false, null, Source.THIRD_PARTY
            );

            pixelCookie.setDescription("Tracking pixel: " + pixelUrl);
            pixelCookie.setCategory("advertising");

            String subdomainName = determineTargetSubdomain(pixelUrl, mainUrl, subdomains);
            pixelCookie.setSubdomainName(subdomainName);

            String cookieKey = generateCookieKey(cookieName, pixelDomain, subdomainName);
            if (!discoveredCookies.containsKey(cookieKey)) {
                discoveredCookies.put(cookieKey, pixelCookie);
                saveIncrementalCookieWithFlush(transactionId, pixelCookie);
            }

        } catch (Exception e) {
            log.warn("Error creating tracking pixel cookie: {}", e.getMessage());
        }
    }

    private void processCookieSettingResponse(Response response, String responseUrl, int status,
                                              Map<String, String> headers, String mainUrl,
                                              List<String> providedSubdomains,
                                              Map<String, CookieDto> discoveredCookies,
                                              String transactionId,
                                              ScanPerformanceTracker.ScanMetrics scanMetrics) {

        // Check for Set-Cookie headers in ANY response
        for (Map.Entry<String, String> header : headers.entrySet()) {
            String headerName = header.getKey().toLowerCase();
            String headerValue = header.getValue();

            if (headerName.equals("set-cookie") || headerName.equals("set-cookie2")) {
                log.info("SET-COOKIE FOUND: {} = {} from {}", headerName, headerValue, responseUrl);

                // Parse and store the cookie
                List<CookieDto> parsedCookies = parseSetCookieHeaderAdvanced(headerValue, responseUrl);

                for (CookieDto cookie : parsedCookies) {
                    // Determine which subdomain this cookie should be attributed to
                    String subdomainName = determineSubdomainAttribution(responseUrl, mainUrl, providedSubdomains);
                    cookie.setSubdomainName(subdomainName);

                    // Determine if first-party or third-party
                    String mainRootDomain = UrlAndCookieUtil.extractRootDomain(mainUrl);
                    String responseRootDomain = UrlAndCookieUtil.extractRootDomain(responseUrl);
                    Source source = mainRootDomain.equalsIgnoreCase(responseRootDomain) ?
                            Source.FIRST_PARTY : Source.THIRD_PARTY;
                    cookie.setSource(source);

                    String cookieKey = generateCookieKey(cookie.getName(), cookie.getDomain(), subdomainName);

                    if (!discoveredCookies.containsKey(cookieKey)) {
                        discoveredCookies.put(cookieKey, cookie);
                        categorizeWithExternalAPI(cookie);
                        saveIncrementalCookieWithFlush(transactionId, cookie);
                        scanMetrics.incrementCookiesFound(source.name());

                        log.info("NETWORK COOKIE CAPTURED: {} from {} (subdomain: {}, source: {})",
                                cookie.getName(), responseUrl, subdomainName, source);
                    }
                }
            }

            // Also check for other tracking headers that might indicate cookie activity
            if (headerName.contains("track") || headerName.contains("analytics") ||
                    headerName.contains("pixel") || headerName.contains("beacon")) {
                log.info("TRACKING HEADER: {} = {} from {}", headerName, headerValue, responseUrl);
            }
        }

        // Check response content for dynamic cookie setting (JavaScript)
        if (headers.getOrDefault("content-type", "").contains("javascript") ||
                headers.getOrDefault("content-type", "").contains("html")) {

            try {
                // This would require fetching response body, which is complex in Playwright
                // For now, just log that we detected a potential dynamic cookie setter
                log.debug("Potential dynamic cookie setter: {}", responseUrl);
            } catch (Exception e) {
                log.debug("Error checking response content: {}", e.getMessage());
            }
        }
    }

    private List<CookieDto> parseSetCookieHeaderAdvanced(String setCookieHeader, String responseUrl) {
        List<CookieDto> cookies = new ArrayList<>();

        try {
            // Handle multiple cookies in one header (comma-separated)
            String[] cookieParts = setCookieHeader.split(",(?=[^;]*=)");

            for (String cookiePart : cookieParts) {
                CookieDto cookie = parseSingleCookie(cookiePart.trim(), responseUrl);
                if (cookie != null) {
                    cookies.add(cookie);
                }
            }

        } catch (Exception e) {
            log.warn("Error parsing Set-Cookie header '{}': {}", setCookieHeader, e.getMessage());

            // Fallback: try to parse as single cookie
            CookieDto fallbackCookie = parseSingleCookie(setCookieHeader, responseUrl);
            if (fallbackCookie != null) {
                cookies.add(fallbackCookie);
            }
        }

        return cookies;
    }

    private CookieDto parseSingleCookie(String cookieString, String responseUrl) {
        try {
            String[] parts = cookieString.split(";");
            if (parts.length == 0) return null;

            // Parse name=value
            String[] nameValue = parts[0].trim().split("=", 2);
            if (nameValue.length != 2) return null;

            String name = nameValue[0].trim();
            String value = nameValue[1].trim();

            // Default values
            String domain = extractDomainFromUrl(responseUrl);
            String path = "/";
            Instant expires = null;
            boolean secure = false;
            boolean httpOnly = false;
            SameSite sameSite = null;

            // Parse attributes
            for (int i = 1; i < parts.length; i++) {
                String attribute = parts[i].trim().toLowerCase();

                if (attribute.startsWith("domain=")) {
                    domain = attribute.substring(7);
                } else if (attribute.startsWith("path=")) {
                    path = attribute.substring(5);
                } else if (attribute.startsWith("expires=")) {
                    try {
                        // Parse expires date - simplified
                        String expiresStr = attribute.substring(8);
                        // You'd need proper date parsing here
                    } catch (Exception e) {
                        // Ignore parsing errors
                    }
                } else if (attribute.equals("secure")) {
                    secure = true;
                } else if (attribute.equals("httponly")) {
                    httpOnly = true;
                } else if (attribute.startsWith("samesite=")) {
                    String sameSiteValue = attribute.substring(9);
                    try {
                        sameSite = SameSite.valueOf(sameSiteValue.toUpperCase());
                    } catch (IllegalArgumentException e) {
                        sameSite = SameSite.LAX; // Default
                    }
                }
            }

            return new CookieDto(name, responseUrl, domain, path, expires,
                    secure, httpOnly, sameSite, Source.UNKNOWN);

        } catch (Exception e) {
            log.warn("Error parsing single cookie '{}': {}", cookieString, e.getMessage());
            return null;
        }
    }

    private String extractDomainFromUrl(String url) {
        try {
            return new URL(url).getHost();
        } catch (Exception e) {
            return "";
        }
    }

    private String determineSubdomainAttribution(String responseUrl, String mainUrl, List<String> providedSubdomains) {
        try {
            String responseHost = new URL(responseUrl).getHost();
            String mainHost = new URL(mainUrl).getHost();

            // If response is from main domain
            if (responseHost.equalsIgnoreCase(mainHost)) {
                return "main";
            }

            // Check if response is from one of the provided subdomains
            if (providedSubdomains != null) {
                for (String subdomainUrl : providedSubdomains) {
                    try {
                        String subdomainHost = new URL(subdomainUrl).getHost();
                        if (responseHost.equalsIgnoreCase(subdomainHost)) {
                            String rootDomain = UrlAndCookieUtil.extractRootDomain(mainUrl);
                            return SubdomainValidationUtil.extractSubdomainName(subdomainUrl, rootDomain);
                        }
                    } catch (Exception e) {
                        // Continue checking
                    }
                }
            }

            // For third-party domains, check if they're related to any subdomain
            // by examining the request context or referrer
            return "main"; // Default attribution

        } catch (Exception e) {
            return "main";
        }
    }

    private void performBackgroundResourceDiscovery(Page page, BrowserContext context, String mainUrl,
                                                    List<String> providedSubdomains,
                                                    Map<String, CookieDto> discoveredCookies,
                                                    String transactionId,
                                                    ScanPerformanceTracker.ScanMetrics scanMetrics) {

        try {
            log.info("Starting background resource discovery...");

            // 1. Monitor for dynamically loaded resources
            Set<String> initialResources = getCurrentPageResources(page);

            // 2. Trigger events that cause background loading
            triggerBackgroundResourceLoading(page);

            // 3. Wait for background resources to load
            page.waitForTimeout(3000);

            // 4. Discover new resources that were loaded
            Set<String> finalResources = getCurrentPageResources(page);
            finalResources.removeAll(initialResources);

            log.info("Discovered {} new background resources", finalResources.size());

            // 5. Test each new resource for cookie setting
            for (String resourceUrl : finalResources) {
                testBackgroundResource(page, context, resourceUrl, mainUrl,
                        providedSubdomains, discoveredCookies, transactionId, scanMetrics);
            }

            // 6. Discover and test iframe resources
            discoverAndTestIframeResources(page, context, mainUrl, providedSubdomains,
                    discoveredCookies, transactionId, scanMetrics);

            // 7. Monitor for WebSocket and EventSource connections
            monitorRealtimeConnections(page, context, mainUrl, providedSubdomains,
                    discoveredCookies, transactionId, scanMetrics);

        } catch (Exception e) {
            log.warn("Error in background resource discovery: {}", e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private Set<String> getCurrentPageResources(Page page) {
        try {
            List<String> resources = (List<String>) page.evaluate("""
            // Get all currently loaded resources
            const resources = new Set();
            
            // From Performance API
            if (window.performance && window.performance.getEntriesByType) {
                const entries = window.performance.getEntriesByType('resource');
                entries.forEach(entry => {
                    if (entry.name && entry.name.startsWith('http')) {
                        resources.add(entry.name);
                    }
                });
            }
            
            // From DOM elements
            const selectors = [
                'script[src]', 'img[src]', 'iframe[src]', 'link[href]',
                'embed[src]', 'object[data]', 'video[src]', 'audio[src]'
            ];
            
            selectors.forEach(selector => {
                document.querySelectorAll(selector).forEach(el => {
                    const url = el.src || el.href || el.data;
                    if (url && url.startsWith('http')) {
                        resources.add(url);
                    }
                });
            });
            
            return Array.from(resources);
        """);

            return new HashSet<>(resources);

        } catch (Exception e) {
            log.debug("Error getting current resources: {}", e.getMessage());
            return new HashSet<>();
        }
    }

    private void triggerBackgroundResourceLoading(Page page) {
        try {
            page.evaluate("""
            console.log('Triggering background resource loading...');
            
            // 1. Scroll to trigger lazy loading
            window.scrollTo(0, document.body.scrollHeight);
            window.scrollTo(0, 0);
            
            // 2. Trigger mouse events to activate lazy components
            const events = ['mousemove', 'mouseenter', 'mouseover', 'focus', 'blur'];
            events.forEach(eventType => {
                document.dispatchEvent(new MouseEvent(eventType, {
                    bubbles: true,
                    cancelable: true,
                    clientX: Math.random() * window.innerWidth,
                    clientY: Math.random() * window.innerHeight
                }));
            });
            
            // 3. Trigger visibility changes (some resources load when page becomes visible)
            if (document.visibilityState === 'visible') {
                Object.defineProperty(document, 'visibilityState', {
                    writable: true,
                    value: 'hidden'
                });
                document.dispatchEvent(new Event('visibilitychange'));
                
                setTimeout(() => {
                    Object.defineProperty(document, 'visibilityState', {
                        writable: true,
                        value: 'visible'
                    });
                    document.dispatchEvent(new Event('visibilitychange'));
                }, 500);
            }
            
            // 4. Trigger resize events (responsive loading)
            window.dispatchEvent(new Event('resize'));
            
            // 5. Simulate user interaction with forms and inputs
            const inputs = document.querySelectorAll('input, select, textarea');
            inputs.forEach(input => {
                try {
                    input.focus();
                    input.dispatchEvent(new Event('focus', {bubbles: true}));
                    input.dispatchEvent(new Event('change', {bubbles: true}));
                    input.blur();
                } catch(e) {}
            });
            
            // 6. Trigger intersection observer patterns (scroll-based loading)
            const observer = new IntersectionObserver((entries) => {
                entries.forEach(entry => {
                    if (entry.isIntersecting) {
                        entry.target.dispatchEvent(new Event('intersect'));
                    }
                });
            });
            
            document.querySelectorAll('div, section, article').forEach(el => {
                observer.observe(el);
            });
            
            // 7. Trigger time-based events
            setTimeout(() => {
                window.dispatchEvent(new Event('load'));
                window.dispatchEvent(new Event('DOMContentLoaded'));
            }, 100);
            
            // 8. Force execution of any pending timeouts/intervals
            if (window.setTimeout.toString().includes('[native code]')) {
                // Trigger any delayed script execution
                for (let i = 0; i < 100; i++) {
                    setTimeout(() => {}, i * 10);
                }
            }
            
            console.log('Background resource loading triggers completed');
        """);

            // Wait for triggered resources to start loading
            page.waitForTimeout(1000);

        } catch (Exception e) {
            log.debug("Error triggering background resource loading: {}", e.getMessage());
        }
    }

    private void testBackgroundResource(Page page, BrowserContext context, String resourceUrl,
                                        String mainUrl, List<String> providedSubdomains,
                                        Map<String, CookieDto> discoveredCookies,
                                        String transactionId, ScanPerformanceTracker.ScanMetrics scanMetrics) {
        try {
            log.debug("Testing background resource: {}", resourceUrl);

            // Navigate to the resource to see if it sets cookies
            try {
                page.navigate(resourceUrl, new Page.NavigateOptions().setTimeout(5000));
                page.waitForTimeout(1000);

                // Capture any cookies set by this resource
                captureBrowserCookiesFromDiscoveredDomain(context, resourceUrl,
                        new URL(resourceUrl).getHost(),
                        mainUrl, providedSubdomains,
                        discoveredCookies, transactionId, scanMetrics);

            } catch (Exception e) {
                log.debug("Background resource test failed for {}: {}", resourceUrl, e.getMessage());
            }

            // Also try common variations of the resource URL
            testResourceVariations(page, context, resourceUrl, mainUrl, providedSubdomains,
                    discoveredCookies, transactionId, scanMetrics);

        } catch (Exception e) {
            log.debug("Error testing background resource {}: {}", resourceUrl, e.getMessage());
        }
    }

    private void testResourceVariations(Page page, BrowserContext context, String baseUrl,
                                        String mainUrl, List<String> providedSubdomains,
                                        Map<String, CookieDto> discoveredCookies,
                                        String transactionId, ScanPerformanceTracker.ScanMetrics scanMetrics) {
        try {
            URL url = new URL(baseUrl);
            String baseHost = url.getHost();
            String basePath = url.getPath();

            // Test common cookie-setting variations
            List<String> variations = Arrays.asList(
                    "https://" + baseHost + "/track",
                    "https://" + baseHost + "/pixel",
                    "https://" + baseHost + "/c.gif",
                    "https://" + baseHost + "/collect",
                    "https://c." + baseHost.replaceFirst("^[^.]+\\.", ""), // c.example.com
                    "https://analytics." + baseHost.replaceFirst("^[^.]+\\.", ""), // analytics.example.com
                    "https://pixel." + baseHost.replaceFirst("^[^.]+\\.", "") // pixel.example.com
            );

            for (String variation : variations) {
                try {
                    page.navigate(variation, new Page.NavigateOptions().setTimeout(3000));
                    page.waitForTimeout(500);

                    captureBrowserCookiesFromDiscoveredDomain(context, variation,
                            new URL(variation).getHost(),
                            mainUrl, providedSubdomains,
                            discoveredCookies, transactionId, scanMetrics);

                } catch (Exception e) {
                    // Expected to fail for many variations
                }
            }

        } catch (Exception e) {
            log.debug("Error testing resource variations: {}", e.getMessage());
        }
    }

    private void discoverAndTestIframeResources(Page page, BrowserContext context, String mainUrl,
                                                List<String> providedSubdomains,
                                                Map<String, CookieDto> discoveredCookies,
                                                String transactionId, ScanPerformanceTracker.ScanMetrics scanMetrics) {
        try {
            log.info("Discovering iframe resources...");

            // Get all iframe sources and test them
            List<String> iframeSources = (List<String>) page.evaluate("""
            Array.from(document.querySelectorAll('iframe'))
                .map(iframe => iframe.src)
                .filter(src => src && src.startsWith('http'))
                .slice(0, 20); // Limit to prevent overload
        """);

            for (String iframeSrc : iframeSources) {
                try {
                    log.debug("Testing iframe resource: {}", iframeSrc);

                    // Navigate to iframe source
                    page.navigate(iframeSrc, new Page.NavigateOptions().setTimeout(5000));
                    page.waitForTimeout(1000);

                    captureBrowserCookiesFromDiscoveredDomain(context, iframeSrc,
                            new URL(iframeSrc).getHost(),
                            mainUrl, providedSubdomains,
                            discoveredCookies, transactionId, scanMetrics);

                    // Also test the iframe domain's common endpoints
                    String iframeDomain = new URL(iframeSrc).getHost();
                    testCommonEndpointsForDomain(page, context, iframeDomain, mainUrl,
                            providedSubdomains, discoveredCookies,
                            transactionId, scanMetrics);

                } catch (Exception e) {
                    log.debug("Iframe resource test failed for {}: {}", iframeSrc, e.getMessage());
                }
            }

        } catch (Exception e) {
            log.warn("Error discovering iframe resources: {}", e.getMessage());
        }
    }

    private void testCommonEndpointsForDomain(Page page, BrowserContext context, String domain,
                                              String mainUrl, List<String> providedSubdomains,
                                              Map<String, CookieDto> discoveredCookies,
                                              String transactionId, ScanPerformanceTracker.ScanMetrics scanMetrics) {

        // Test common cookie-setting endpoints for any discovered domain
        List<String> commonEndpoints = Arrays.asList(
                "/", "/track", "/pixel", "/beacon", "/collect", "/analytics", "/c.gif", "/px.gif",
                "/tr", "/hit", "/log", "/event", "/sync", "/match", "/redirect", "/r"
        );

        for (String endpoint : commonEndpoints) {
            try {
                String testUrl = "https://" + domain + endpoint;
                page.navigate(testUrl, new Page.NavigateOptions().setTimeout(2000));
                page.waitForTimeout(300);

                captureBrowserCookiesFromDiscoveredDomain(context, testUrl, domain,
                        mainUrl, providedSubdomains,
                        discoveredCookies, transactionId, scanMetrics);

            } catch (Exception e) {
                // Expected to fail for many endpoints
            }
        }
    }

    private void monitorRealtimeConnections(Page page, BrowserContext context, String mainUrl,
                                            List<String> providedSubdomains,
                                            Map<String, CookieDto> discoveredCookies,
                                            String transactionId, ScanPerformanceTracker.ScanMetrics scanMetrics) {
        try {
            // Monitor for WebSocket connections and Server-Sent Events
            page.evaluate("""
            // Override WebSocket to monitor connections
            const originalWebSocket = window.WebSocket;
            window.WebSocket = function(url, protocols) {
                console.log('WebSocket connection to:', url);
                window.cookieScannerWebSocketUrls = window.cookieScannerWebSocketUrls || [];
                window.cookieScannerWebSocketUrls.push(url);
                return new originalWebSocket(url, protocols);
            };
            
            // Override EventSource to monitor SSE connections
            const originalEventSource = window.EventSource;
            window.EventSource = function(url, options) {
                console.log('EventSource connection to:', url);
                window.cookieScannerEventSourceUrls = window.cookieScannerEventSourceUrls || [];
                window.cookieScannerEventSourceUrls.push(url);
                return new originalEventSource(url, options);
            };
            
            // Trigger potential real-time connections
            window.dispatchEvent(new Event('online'));
            window.dispatchEvent(new Event('focus'));
        """);

            page.waitForTimeout(2000);

            // Get discovered real-time connection URLs
            List<String> wsUrls = getDiscoveredUrls(page, "window.cookieScannerWebSocketUrls");
            List<String> sseUrls = getDiscoveredUrls(page, "window.cookieScannerEventSourceUrls");

            // Test these URLs for cookie setting
            for (String url : wsUrls) {
                testRealtimeUrl(page, context, url, mainUrl, providedSubdomains,
                        discoveredCookies, transactionId, scanMetrics);
            }

            for (String url : sseUrls) {
                testRealtimeUrl(page, context, url, mainUrl, providedSubdomains,
                        discoveredCookies, transactionId, scanMetrics);
            }

        } catch (Exception e) {
            log.debug("Error monitoring realtime connections: {}", e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private List<String> getDiscoveredUrls(Page page, String jsVariable) {
        try {
            return (List<String>) page.evaluate("return " + jsVariable + " || []");
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    private void testRealtimeUrl(Page page, BrowserContext context, String url,
                                 String mainUrl, List<String> providedSubdomains,
                                 Map<String, CookieDto> discoveredCookies,
                                 String transactionId, ScanPerformanceTracker.ScanMetrics scanMetrics) {
        try {
            // Convert WebSocket/SSE URLs to HTTP for testing
            String httpUrl = url.replaceFirst("^ws:", "http:").replaceFirst("^wss:", "https:");

            page.navigate(httpUrl, new Page.NavigateOptions().setTimeout(3000));
            page.waitForTimeout(500);

            captureBrowserCookiesFromDiscoveredDomain(context, httpUrl,
                    new URL(httpUrl).getHost(),
                    mainUrl, providedSubdomains,
                    discoveredCookies, transactionId, scanMetrics);

        } catch (Exception e) {
            log.debug("Realtime URL test failed for {}: {}", url, e.getMessage());
        }
    }

    private boolean isThirdPartyRequest(String requestUrl, String mainUrl) {
        try {
            String requestDomain = UrlAndCookieUtil.extractRootDomain(requestUrl);
            String mainDomain = UrlAndCookieUtil.extractRootDomain(mainUrl);
            return !requestDomain.equalsIgnoreCase(mainDomain);
        } catch (Exception e) {
            return true; // Assume third-party if can't determine
        }
    }

    private void extractCookiesFromUrlParameters(String url, String mainUrl, List<String> subdomains,
                                                 Map<String, CookieDto> discoveredCookies,
                                                 String transactionId) {
        try {
            URL parsedUrl = new URL(url);
            String query = parsedUrl.getQuery();

            if (query != null && !query.isEmpty()) {
                String[] params = query.split("&");

                for (String param : params) {
                    String[] parts = param.split("=", 2);
                    if (parts.length == 2) {
                        String paramName = parts[0];
                        String paramValue = parts[1];

                        // Check if parameter looks like tracking data
                        if (paramName.length() <= 10 && paramValue.length() >= 8) {
                            String cookieName = "url_tracking_" + paramName;
                            String domain = parsedUrl.getHost();

                            CookieDto trackingCookie = new CookieDto(
                                    cookieName, url, domain, "/", null,
                                    false, false, null, Source.THIRD_PARTY
                            );

                            trackingCookie.setDescription("URL tracking parameter: " + paramName);
                            trackingCookie.setSubdomainName("main");

                            String cookieKey = generateCookieKey(cookieName, domain, "main");

                            if (!discoveredCookies.containsKey(cookieKey)) {
                                discoveredCookies.put(cookieKey, trackingCookie);
                                saveIncrementalCookieWithFlush(transactionId, trackingCookie);
                                log.info("URL PARAM COOKIE: {} from {}", cookieName, url);
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.debug("Error extracting cookies from URL parameters: {}", e.getMessage());
        }
    }


    private List<CookieDto> parseSetCookieHeader(String setCookieHeader, String responseUrl, String requestUrl) {
        List<CookieDto> cookies = new ArrayList<>();

        try {
            // Split multiple cookies (comma-separated, but be careful with expires dates)
            String[] cookieParts = setCookieHeader.split(",(?=[^;]*=)");

            for (String cookiePart : cookieParts) {
                String trimmed = cookiePart.trim();
                if (!trimmed.isEmpty()) {
                    CookieDto cookie = parseSingleSetCookie(trimmed, responseUrl);
                    if (cookie != null) {
                        cookies.add(cookie);
                    }
                }
            }

            // Fallback: if no cookies parsed, try parsing the whole string as one cookie
            if (cookies.isEmpty()) {
                CookieDto fallback = parseSingleSetCookie(setCookieHeader, responseUrl);
                if (fallback != null) {
                    cookies.add(fallback);
                }
            }

        } catch (Exception e) {
            log.warn("Error parsing Set-Cookie header: {}", e.getMessage());
        }

        return cookies;
    }

    private CookieDto parseSingleSetCookie(String cookieString, String responseUrl) {
        try {
            String[] parts = cookieString.split(";");
            if (parts.length == 0) return null;

            // Parse name=value
            String[] nameValue = parts[0].trim().split("=", 2);
            if (nameValue.length != 2) return null;

            String name = nameValue[0].trim();
            String value = nameValue[1].trim();

            // Default values
            String domain = extractDomainFromUrl(responseUrl);
            String path = "/";
            Instant expires = null;
            boolean secure = false;
            boolean httpOnly = false;
            SameSite sameSite = null;

            // Parse cookie attributes
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
                    try {
                        sameSite = SameSite.valueOf(sameSiteValue.toUpperCase());
                    } catch (IllegalArgumentException e) {
                        sameSite = SameSite.LAX;
                    }
                }
            }

            return new CookieDto(name, responseUrl, domain, path, expires,
                    secure, httpOnly, sameSite, Source.UNKNOWN);

        } catch (Exception e) {
            log.warn("Error parsing single cookie '{}': {}", cookieString, e.getMessage());
            return null;
        }
    }

    private void forceVisitCookieSettingDomains(Page page, BrowserContext context, String mainUrl,
                                                Map<String, CookieDto> discoveredCookies,
                                                String transactionId,
                                                ScanPerformanceTracker.ScanMetrics scanMetrics) {

        // Get all domains referenced on the page
        List<String> referencedDomains = (List<String>) page.evaluate("""
        Array.from(document.querySelectorAll('[src], [href]'))
            .map(el => {
                try {
                    const url = el.src || el.href;
                    return new URL(url).hostname;
                } catch(e) { return null; }
            })
            .filter(Boolean)
            .filter((v, i, a) => a.indexOf(v) === i)
            .slice(0, 30);
    """);

        log.info("Force visiting {} referenced domains for cookie detection", referencedDomains.size());

        // Visit each domain directly
        for (String domain : referencedDomains) {
            try {
                // Try the domain root
                page.navigate("https://" + domain, new Page.NavigateOptions().setTimeout(5000));
                page.waitForTimeout(1000);
                captureBrowserCookiesEnhanced(context, "https://" + domain, discoveredCookies, transactionId, scanMetrics);

                // Try common cookie endpoints
                String[] endpoints = {"/", "/track", "/pixel", "/c.gif", "/px.gif"};
                for (String endpoint : endpoints) {
                    try {
                        page.navigate("https://" + domain + endpoint, new Page.NavigateOptions().setTimeout(3000));
                        page.waitForTimeout(500);
                        captureBrowserCookiesEnhanced(context, "https://" + domain + endpoint, discoveredCookies, transactionId, scanMetrics);
                    } catch (Exception e) {
                        // Expected to fail, continue
                    }
                }
            } catch (Exception e) {
                log.debug("Failed to visit domain {}: {}", domain, e.getMessage());
            }
        }
    }
}