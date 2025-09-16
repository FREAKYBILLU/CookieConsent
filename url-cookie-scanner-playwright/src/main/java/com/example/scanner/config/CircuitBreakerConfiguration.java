// File: src/main/java/com/example/scanner/config/CircuitBreakerConfig.java
package com.example.scanner.config;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

@Configuration
public class CircuitBreakerConfiguration {

    @Bean
    public CircuitBreakerRegistry circuitBreakerRegistry() {
        return CircuitBreakerRegistry.ofDefaults();
    }

    @Bean
    public CircuitBreaker scanCircuitBreaker(CircuitBreakerRegistry circuitBreakerRegistry) {
        CircuitBreakerConfig config = CircuitBreakerConfig.custom()
                .failureRateThreshold(50)                    // Open when 50% of requests fail
                .waitDurationInOpenState(Duration.ofSeconds(30))  // Stay open for 30 seconds
                .slidingWindowSize(20)                       // Look at last 20 requests
                .minimumNumberOfCalls(10)                    // Need at least 10 calls to calculate failure rate
                .permittedNumberOfCallsInHalfOpenState(5)    // Allow 5 test calls when half-open
                .slowCallRateThreshold(50)                   // Consider calls slower than duration threshold as slow
                .slowCallDurationThreshold(Duration.ofSeconds(10)) // Calls taking > 10s are slow
                .recordExceptions(
                        RuntimeException.class,
                        Exception.class
                )
                .build();

        return circuitBreakerRegistry.circuitBreaker("scanService", config);
    }

    @Bean
    public CircuitBreaker categorizationCircuitBreaker(CircuitBreakerRegistry circuitBreakerRegistry) {
        CircuitBreakerConfig config = CircuitBreakerConfig.custom()
                .failureRateThreshold(60)                    // More lenient for external API
                .waitDurationInOpenState(Duration.ofSeconds(20))
                .slidingWindowSize(10)
                .minimumNumberOfCalls(5)
                .permittedNumberOfCallsInHalfOpenState(3)
                .slowCallRateThreshold(70)
                .slowCallDurationThreshold(Duration.ofSeconds(15))
                .recordExceptions(
                        org.springframework.web.client.RestClientException.class,
                        org.springframework.web.client.ResourceAccessException.class,
                        java.net.SocketTimeoutException.class
                )
                .build();

        return circuitBreakerRegistry.circuitBreaker("categorizationService", config);
    }
}