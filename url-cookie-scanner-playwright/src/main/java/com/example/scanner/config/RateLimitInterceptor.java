// File: src/main/java/com/example/scanner/config/RateLimitInterceptor.java
package com.example.scanner.config;

import com.example.scanner.dto.ErrorResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.ConsumptionProbe;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.time.Instant;

@Component
public class RateLimitInterceptor implements HandlerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(RateLimitInterceptor.class);

    @Autowired
    private RateLimitingConfig rateLimitingConfig;

    @Autowired
    private ObjectMapper objectMapper;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        log.info("INTERCEPTOR TRIGGERED for path: {}", request.getRequestURI());

        String requestPath = request.getRequestURI();
        log.info("Rate limit interceptor triggered for path: {}", requestPath);

        // Get client identifier (IP + User-Agent hash for better uniqueness)
        String clientId = getClientId(request);
        log.info("Client ID: {}", clientId);

        // Get bucket for this client
        Bucket bucket = rateLimitingConfig.resolveBucket(clientId);
        log.info("Bucket retrieved for client: {}", clientId);

        // Try to consume 1 token
        ConsumptionProbe probe = bucket.tryConsumeAndReturnRemaining(1);
        log.info("Rate limit check - consumed: {}, remaining: {}", probe.isConsumed(), probe.getRemainingTokens());

        if (probe.isConsumed()) {
            // Add rate limit headers for transparency
            response.addHeader("X-Rate-Limit-Remaining", String.valueOf(probe.getRemainingTokens()));
            log.info("Request allowed - remaining tokens: {}", probe.getRemainingTokens());
            response.addHeader("X-Rate-Limit-Total", "100");
            response.addHeader("X-Rate-Limit-Window", "60s");

            log.debug("Request allowed for client: {} (remaining: {})", clientId, probe.getRemainingTokens());
            return true;
        } else {
            log.warn("RATE LIMIT EXCEEDED for client: {}", clientId);
            long waitForRefill = probe.getNanosToWaitForRefill() / 1_000_000_000; // Convert to seconds

            response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.addHeader("X-Rate-Limit-Remaining", "0");
            response.addHeader("X-Rate-Limit-Total", "100");
            response.addHeader("X-Rate-Limit-Window", "60s");
            response.addHeader("Retry-After", String.valueOf(waitForRefill));

            ErrorResponse errorResponse = new ErrorResponse(
                    "R4291",
                    "Too many requests. Please slow down and try again later.",
                    "Rate limit exceeded for client: " + clientId + ". Retry after: " + waitForRefill + " seconds",
                    Instant.now(),
                    request.getRequestURI()
            );

            response.getWriter().write(objectMapper.writeValueAsString(errorResponse));

            log.warn("Rate limit exceeded for client: {} on endpoint: {}. Retry after: {} seconds",
                    clientId, request.getRequestURI(), waitForRefill);

            return false;
        }
    }

    private String getClientId(HttpServletRequest request) {
        // Use IP + User-Agent hash to create unique client ID
        String clientIp = getClientIpAddress(request);
        String userAgent = request.getHeader("User-Agent");

        // Create a more unique identifier to prevent rate limit bypass
        String combined = clientIp + ":" + (userAgent != null ? userAgent.hashCode() : "unknown");
        return combined;
    }

    private String getClientIpAddress(HttpServletRequest request) {
        // Check for IP in various headers (for load balancers/proxies)
        String[] ipHeaders = {
                "X-Forwarded-For",
                "X-Real-IP",
                "X-Client-IP",
                "CF-Connecting-IP", // Cloudflare
                "True-Client-IP"    // Akamai
        };

        for (String header : ipHeaders) {
            String ip = request.getHeader(header);
            if (ip != null && !ip.isEmpty() && !"unknown".equalsIgnoreCase(ip)) {
                // X-Forwarded-For can contain multiple IPs, take the first one
                if (ip.contains(",")) {
                    ip = ip.split(",")[0].trim();
                }
                return ip;
            }
        }

        // Fallback to remote address
        return request.getRemoteAddr();
    }
}