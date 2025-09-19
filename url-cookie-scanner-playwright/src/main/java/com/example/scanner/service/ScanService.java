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
import org.springframework.beans.factory.annotation.Value;
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

    @Value("${scanner.cookie.collection.phases:2}")
    private int cookieCollectionPhases;

    @Value("${scanner.cookie.collection.interval.ms:1500}")
    private int cookieCollectionInterval;

    private final ScanResultRepository repository;
    private final CookieCategorizationService cookieCategorizationService;
    private final CookieScanMetrics metrics;

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

            for (int phase = 1; phase <= cookieCollectionPhases; phase++) {
                log.info("Cookie collection phase {} of {}", phase, cookieCollectionPhases);
                captureBrowserCookiesEnhanced(context, url, discoveredCookies, transactionId, scanMetrics);

                if (phase < cookieCollectionPhases) {
                    page.waitForTimeout(cookieCollectionInterval);
                }
            }

            if ((Boolean) page.evaluate("document.querySelectorAll('iframe, embed, object').length > 0")) {
                log.info("Embedded content detected - extending wait time");
                page.waitForTimeout(1500);
            }

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

            triggerPatternBasedCookieDiscovery(page, context, url, discoveredCookies, transactionId, scanMetrics);
            //detectGenericAuthenticationFlow(page, context, url, discoveredCookies, transactionId, scanMetrics);
            detectGenericPixelTracking(page, context, url, discoveredCookies, transactionId, scanMetrics);
            detectGenericApplicationState(page, context, url, discoveredCookies, transactionId, scanMetrics);

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
//            log.info("=== PHASE 3.5: Generic authentication flow detection ===");
//            //detectAndTriggerAuthenticationFlows(page, url, transactionId, discoveredCookies, scanMetrics);
//            log.info("Captured storage after authentication triggers");

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

                // SAFE SCROLL: Check for document.body existence and use fallbacks
                page.evaluate(String.format("""
        // Safe scroll implementation with multiple fallbacks
        (function() {
            try {
                let scrollTarget = 0;
                let totalHeight = 0;
                
                // Try different methods to get document height
                if (document.body && document.body.scrollHeight) {
                    totalHeight = document.body.scrollHeight;
                } else if (document.documentElement && document.documentElement.scrollHeight) {
                    totalHeight = document.documentElement.scrollHeight;
                } else if (document.scrollingElement && document.scrollingElement.scrollHeight) {
                    totalHeight = document.scrollingElement.scrollHeight;
                } else {
                    // Fallback to window height
                    totalHeight = window.innerHeight || screen.height || 1000;
                }
                
                scrollTarget = Math.floor(totalHeight * %f);
                
                // Safe scroll
                if (scrollTarget >= 0) {
                    window.scrollTo(0, scrollTarget);
                    console.log('Scrolled to:', scrollTarget, 'of', totalHeight);
                } else {
                    console.log('Invalid scroll target, skipping scroll');
                }
                
            } catch(e) {
                console.log('Scroll operation failed:', e.message);
                // Fallback: just scroll to a fixed position
                try {
                    window.scrollTo(0, %d);
                } catch(e2) {
                    console.log('Fallback scroll also failed:', e2.message);
                }
            }
        })();
    """, scrollPercent, (int)(scrollPercent * 1000)));

                // SAFE ANALYTICS TRIGGERING: Also fix the gtag call
                page.evaluate("""
        (function() {
            try {
                window.dispatchEvent(new Event('scroll'));
                window.dispatchEvent(new Event('resize'));
                
                // Safe gtag call with proper height calculation
                if (typeof gtag === 'function') {
                    let currentScroll = window.pageYOffset || document.documentElement.scrollTop || 0;
                    let totalHeight = 0;
                    
                    if (document.body && document.body.scrollHeight) {
                        totalHeight = document.body.scrollHeight;
                    } else if (document.documentElement && document.documentElement.scrollHeight) {
                        totalHeight = document.documentElement.scrollHeight;
                    } else {
                        totalHeight = window.innerHeight || 1000;
                    }
                    
                    let percentScrolled = totalHeight > 0 ? Math.round((currentScroll / totalHeight) * 100) : 0;
                    
                    gtag('event', 'scroll', {
                        percent_scrolled: percentScrolled
                    });
                }
            } catch(e) {
                console.log('Analytics event failed:', e.message);
            }
        })();
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

                for (int i = 0; i < maxSubdomainsToScan; i++) {
                    String subdomain = subdomains.get(i);
                    String subdomainName = SubdomainValidationUtil.extractSubdomainName(subdomain, rootDomain);

                    log.info("=== ESSENTIAL SUBDOMAIN {}/{}: {} (Name: {}) ===",
                            i+1, maxSubdomainsToScan, subdomain, subdomainName);

                    try {
                        Response subdomainResponse = page.navigate(subdomain, new Page.NavigateOptions()
                                .setWaitUntil(WaitUntilState.DOMCONTENTLOADED) // FASTER
                                .setTimeout(8000)); // SHORTER timeout
                        log.info("PAGE NAVIGATION DONE");
                        if (subdomainResponse != null && subdomainResponse.ok()) {
                            page.waitForTimeout(1500);

                            captureBrowserCookiesWithSubdomainName(context, subdomain, discoveredCookies,
                                    transactionId, scanMetrics, subdomainName);
                            log.info("CAPTURE BROWSER COOKIES WITH SUBDOMAIN NAME");
                            try {
                                boolean subdomainConsentHandled = CookieDetectionUtil.handleConsentBanners(page, 5000); // SHORTER
                                log.info("HANDLED CONSENT BANNER");
                                if (subdomainConsentHandled) {
                                    page.waitForTimeout(1000);
                                    captureBrowserCookiesWithSubdomainName(context, subdomain, discoveredCookies,
                                            transactionId, scanMetrics, subdomainName);
                                    log.info("CAPTURE BROWSER COOKIES WITH SUBDOMAIN NAME");
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
            log.info("=== SINGLE FINAL CAPTURE ===");
            page.waitForTimeout(1000);
            captureBrowserCookiesEnhanced(context, url, discoveredCookies, transactionId, scanMetrics);
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

            // NEW: List to collect cookies for saving
            List<CookieDto> cookiesToSave = new ArrayList<>();

            // MODIFIED: Loop to collect cookies without saving
            for (Cookie playwrightCookie : browserCookies) {
                try {
                    CookieDto cookieDto = mapPlaywrightCookie(playwrightCookie, scanUrl, siteRoot);
                    cookieDto.setSubdomainName(subdomainName);

                    String cookieKey = generateCookieKey(cookieDto.getName(), cookieDto.getDomain(), cookieDto.getSubdomainName());

                    if (!discoveredCookies.containsKey(cookieKey)) {
                        discoveredCookies.put(cookieKey, cookieDto);
                        cookiesToSave.add(cookieDto);  // ADD to save list instead of immediate save
                        scanMetrics.incrementCookiesFound(cookieDto.getSource().name());
                        metrics.recordCookieDiscovered(cookieDto.getSource().name());
                        log.debug("Collected SUBDOMAIN BROWSER COOKIE: {} from domain {} (Source: {}, Subdomain: {})",
                                cookieDto.getName(), cookieDto.getDomain(), cookieDto.getSource(), subdomainName);
                    }
                } catch (Exception e) {
                    log.warn("Error processing browser cookie {}: {}", playwrightCookie.name, e.getMessage());
                }
            }

            // NEW: Categorize all collected cookies and save them
            categorizeCookiesAndSave(cookiesToSave, transactionId);

            log.info("Completed capturing and saving {} subdomain cookies", cookiesToSave.size());

        } catch (Exception e) {
            log.warn("Error capturing browser cookies for subdomain {}: {}", subdomainName, e.getMessage());
        }
    }


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

            // NEW: List to collect cookies for saving
            List<CookieDto> cookiesToSave = new ArrayList<>();

            // MODIFIED: Loop to collect cookies without saving
            for (Cookie playwrightCookie : browserCookies) {
                try {
                    CookieDto cookieDto = mapPlaywrightCookie(playwrightCookie, scanUrl, siteRoot);
                    cookieDto.setSubdomainName("main");

                    String cookieKey = generateCookieKey(cookieDto.getName(), cookieDto.getDomain(), cookieDto.getSubdomainName());

                    if (!discoveredCookies.containsKey(cookieKey)) {
                        discoveredCookies.put(cookieKey, cookieDto);
                        cookiesToSave.add(cookieDto);  // ADD to save list instead of immediate save
                        scanMetrics.incrementCookiesFound(cookieDto.getSource().name());
                        metrics.recordCookieDiscovered(cookieDto.getSource().name());
                        log.debug("Collected BROWSER COOKIE: {} from domain {} (Source: {})",
                                cookieDto.getName(), cookieDto.getDomain(), cookieDto.getSource());
                    }
                } catch (Exception e) {
                    log.warn("Error processing browser cookie {}: {}", playwrightCookie.name, e.getMessage());
                }
            }

            // NEW: Categorize all collected cookies and save them
            categorizeCookiesAndSave(cookiesToSave, transactionId);

            log.info("Completed capturing and saving {} browser cookies", cookiesToSave.size());

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

            // ENHANCED: Better frame processing with URL validation
            for (Frame frame : page.frames()) {
                try {
                    String frameUrl = frame.url();

                    // FIX: Add validation for empty or invalid URLs
                    if (frameUrl == null || frameUrl.trim().isEmpty() ||
                            frameUrl.equals("about:blank") || frameUrl.equals(url)) {
                        log.debug("Skipping frame with invalid/empty URL: {}", frameUrl);
                        continue;
                    }

                    // FIX: Additional validation for proper URL format
                    if (!frameUrl.startsWith("http://") && !frameUrl.startsWith("https://")) {
                        log.debug("Skipping frame with non-HTTP URL: {}", frameUrl);
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
                        log.debug("Frame js execution failed for {}: {}", frameUrl, e.getMessage());
                    }

                    // FIX: Add URL validation before getting cookies
                    try {
                        // Validate URL before passing to cookies()
                        new URL(frameUrl); // This will throw if invalid

                        List<Cookie> frameCookies = page.context().cookies(frameUrl);
                        String frameRoot = UrlAndCookieUtil.extractRootDomain(frameUrl);
                        String siteRoot = UrlAndCookieUtil.extractRootDomain(url);
                        Source frameSource = siteRoot.equalsIgnoreCase(frameRoot) ? Source.FIRST_PARTY : Source.THIRD_PARTY;

                        // NEW: List to collect frame cookies for saving
                        List<CookieDto> frameCookiesToSave = new ArrayList<>();

                        for (Cookie frameCookie : frameCookies) {
                            CookieDto cookieDto = mapPlaywrightCookie(frameCookie, frameUrl, frameRoot);
                            cookieDto.setSource(frameSource);

                            String subdomainName = determineSubdomainNameFromUrl(frameUrl, url, null);
                            cookieDto.setSubdomainName(subdomainName);

                            String cookieKey = generateCookieKey(cookieDto.getName(), cookieDto.getDomain(), cookieDto.getSubdomainName());
                            if (!discoveredCookies.containsKey(cookieKey)) {
                                discoveredCookies.put(cookieKey, cookieDto);
                                frameCookiesToSave.add(cookieDto);  // ADD to save list instead of immediate save
                                scanMetrics.incrementCookiesFound(frameSource.name());
                                metrics.recordCookieDiscovered(frameSource.name());
                                log.debug("Collected FRAME COOKIE: {} from {} (Source: {})",
                                        cookieDto.getName(), frameUrl, frameSource);
                            }
                        }

                        // NEW: Categorize all collected frame cookies and save them
                        categorizeCookiesAndSave(frameCookiesToSave, transactionId);

                    } catch (java.net.MalformedURLException e) {
                        log.debug("Invalid frame URL format, skipping cookie capture: {}", frameUrl);
                    } catch (Exception e) {
                        log.debug("Error capturing cookies from frame {}: {}", frameUrl, e.getMessage());
                    }

                } catch (Exception e) {
                    log.debug("Error processing frame: {}", e.getMessage());
                }
            }

            // Rest of the method continues unchanged...
            interactWithAllIframes(page, iframes);
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

    private void cleanupResources(BrowserContext context, Browser browser, Playwright playwright) {
        // Close pages first
        try {
            if (context != null) {
                List<Page> pages = context.pages();
                for (Page page : pages) {
                    try {
                        if (!page.isClosed()) page.close();
                    } catch (Exception ignored) {}
                }
                context.close();
            }
        } catch (Exception e) {
            log.warn("Error closing context: {}", e.getMessage());
        }

        // Close browser
        try {
            if (browser != null && browser.isConnected()) {
                browser.close();
            }
        } catch (Exception e) {
            log.warn("Error closing browser: {}", e.getMessage());
        }

        // Close playwright
        try {
            if (playwright != null) playwright.close();
        } catch (Exception e) {
            log.warn("Error closing playwright: {}", e.getMessage());
        }

        // Force garbage collection
        System.gc();
    }

    private String getErrorMessage(Exception e) {
        if (e instanceof ScannerException) {
            return ((ScannerException) e).getUserMessage();
        }
        return "An unexpected error occurred during scanning";
    }

    private void categorizeCookiesAndSave(List<CookieDto> cookiesToSave, String transactionId) {
        if (cookiesToSave.isEmpty()) {
            return;
        }

        try {
            // Step 1: Extract unique cookie names
            Set<String> uniqueCookieNames = cookiesToSave.stream()
                    .map(CookieDto::getName)
                    .collect(Collectors.toSet());

            List<String> cookieNames = new ArrayList<>(uniqueCookieNames);
            log.info("Categorizing {} unique cookie names with ONE API call (from {} cookies to save)",
                    cookieNames.size(), cookiesToSave.size());

            // Step 2: Make single API call for all unique names
            Map<String, CookieCategorizationResponse> results =
                    cookieCategorizationService.categorizeCookies(cookieNames);

            // Step 3: Apply categorization to all cookies
            for (CookieDto cookie : cookiesToSave) {
                String cookieName = cookie.getName();
                CookieCategorizationResponse response = results.get(cookieName);

                if (response != null) {
                    cookie.setCategory(response.getCategory());
                    cookie.setDescription(response.getDescription());
                    cookie.setDescription_gpt(response.getDescription_gpt());
                    log.debug("Applied categorization to cookie '{}'", cookieName);
                } else {
                    log.warn("No categorization response found for cookie '{}'", cookieName);
                }
            }

            // Step 4: Save all categorized cookies to database
            for (CookieDto categorizedCookie : cookiesToSave) {
                saveIncrementalCookieWithFlush(transactionId, categorizedCookie);
            }

            log.info("Successfully categorized and saved {} cookies", cookiesToSave.size());

        } catch (Exception e) {
            log.error("Failed to categorize and save cookies: {}", e.getMessage(), e);

            // Fallback: Save cookies without categorization
            log.info("Falling back to save cookies without categorization");
            for (CookieDto cookie : cookiesToSave) {
                try {
                    saveIncrementalCookieWithFlush(transactionId, cookie);
                } catch (Exception saveEx) {
                    log.warn("Failed to save cookie '{}': {}", cookie.getName(), saveEx.getMessage());
                }
            }
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

            // FIX: Wrap the JavaScript code in an IIFE (Immediately Invoked Function Expression)
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

            // Rest of your method continues unchanged...
            for (Map.Entry<String, String> entry : domainPatterns.entrySet()) {
                String domain = entry.getKey();
                String purpose = entry.getValue();

                List<String> endpointsToTry = getEndpointsForPurpose(purpose);

                for (String endpoint : endpointsToTry) {
                    try {
                        String testUrl = "https://" + domain + endpoint;
                        page.navigate(testUrl, new Page.NavigateOptions().setTimeout(2000));
                        page.waitForTimeout(500);
                        captureBrowserCookiesEnhanced(context, testUrl, discoveredCookies, transactionId, scanMetrics);
                    } catch (Exception e) {
                        // Expected to fail for many URLs - continue silently
                    }
                }
            }

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
}