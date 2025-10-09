package com.example.scanner.exception;

import com.example.scanner.constants.ErrorCodes;
import com.example.scanner.dto.response.ErrorResponse;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.client.RestClientException;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.http.HttpHeaders;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    // ==================== RATE LIMITING AND CIRCUIT BREAKER EXCEPTIONS ====================

    @ExceptionHandler(CallNotPermittedException.class)
    public ResponseEntity<ErrorResponse> handleCircuitBreakerOpen(
            CallNotPermittedException ex, WebRequest request) {

        log.error("Circuit breaker is OPEN - service unavailable: {}", ex.getMessage());

        ErrorResponse error = new ErrorResponse(
                "R5031", // Service Unavailable - Circuit Breaker
                "Service is temporarily unavailable due to high error rate. Please try again in a few minutes.",
                "Circuit breaker is OPEN: " + ex.getMessage(),
                Instant.now(),
                request.getDescription(false)
        );

        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .header("Retry-After", "30") // Suggest retry after 30 seconds
                .body(error);
    }

    @ExceptionHandler(RejectedExecutionException.class)
    public ResponseEntity<ErrorResponse> handleThreadPoolRejection(
            RejectedExecutionException ex, WebRequest request) {

        log.error("Thread pool rejected execution - system overloaded: {}", ex.getMessage());

        ErrorResponse error = new ErrorResponse(
                "R5032", // Service Unavailable - Thread Pool Full
                "System is currently overloaded. Please reduce request rate and try again later.",
                "Thread pool rejection: " + ex.getMessage(),
                Instant.now(),
                request.getDescription(false)
        );

        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .header("Retry-After", "60") // Suggest retry after 60 seconds
                .body(error);
    }

    @ExceptionHandler(TimeoutException.class)
    public ResponseEntity<ErrorResponse> handleTimeout(
            TimeoutException ex, WebRequest request) {

        log.error("Request timeout occurred: {}", ex.getMessage());

        ErrorResponse error = new ErrorResponse(
                "R5033", // Service Unavailable - Timeout
                "Request timed out. The server is taking too long to respond. Please try again later.",
                "Timeout exception: " + ex.getMessage(),
                Instant.now(),
                request.getDescription(false)
        );

        return ResponseEntity.status(HttpStatus.REQUEST_TIMEOUT).body(error);
    }

    @ExceptionHandler(OutOfMemoryError.class)
    public ResponseEntity<ErrorResponse> handleOutOfMemory(
            OutOfMemoryError ex, WebRequest request) {

        log.error("CRITICAL: Out of memory error occurred: {}", ex.getMessage());

        ErrorResponse error = new ErrorResponse(
                "R5034", // Critical Error - Out of Memory
                "System is experiencing memory issues. Please try again later or contact support.",
                "Out of memory error: " + ex.getMessage(),
                Instant.now(),
                request.getDescription(false)
        );

        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(error);
    }

    @ExceptionHandler(StackOverflowError.class)
    public ResponseEntity<ErrorResponse> handleStackOverflow(
            StackOverflowError ex, WebRequest request) {

        log.error("CRITICAL: Stack overflow error occurred: {}", ex.getMessage());

        ErrorResponse error = new ErrorResponse(
                "R5035", // Critical Error - Stack Overflow
                "System encountered a processing error. Please contact support if this persists.",
                "Stack overflow error: " + ex.getMessage(),
                Instant.now(),
                request.getDescription(false)
        );

        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(error);
    }

    @ExceptionHandler(UrlValidationException.class)
    public ResponseEntity<ErrorResponse> handleUrlValidation(UrlValidationException ex, WebRequest request) {
        log.warn("URL validation failed: {}", ex.getMessage());

        ErrorResponse error = new ErrorResponse(
                ex.getErrorCode(),
                ex.getUserMessage(),
                ex.getDeveloperDetails(),
                Instant.now(),
                request.getDescription(false)
        );

        return ResponseEntity.badRequest().body(error);
    }
    @ExceptionHandler(CookieNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleCookieNotFoundException(CookieNotFoundException ex, HttpServletRequest request) {
        ErrorResponse errorResponse = new ErrorResponse(
                ex.getErrorCode(),
                ex.getUserMessage(),                           // User-friendly message
                ex.getDeveloperDetails(),                      // Developer details
                Instant.now(),
                request.getRequestURI()
        );

        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(errorResponse);
    }

    @ExceptionHandler(TransactionNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleTransactionNotFound(TransactionNotFoundException ex, WebRequest request) {
        log.warn("Transaction not found: {}", ex.getMessage());

        ErrorResponse error = new ErrorResponse(
                ex.getErrorCode(),
                ex.getUserMessage(),           // User-friendly: "The requested scan transaction was not found"
                ex.getDeveloperDetails(),      // Developer details: "No scan record exists in database for ID: abc123"
                Instant.now(),
                request.getDescription(false)
        );

        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(error);
    }

    @ExceptionHandler(ScanExecutionException.class)
    public ResponseEntity<ErrorResponse> handleScanExecution(ScanExecutionException ex, WebRequest request) {
        log.error("Scan execution failed: {}", ex.getMessage(), ex);

        ErrorResponse error = new ErrorResponse(
                ex.getErrorCode(),
                ex.getUserMessage(),           // User-friendly: "Unable to complete the scan. Please try again later"
                ex.getDeveloperDetails(),      // Developer details: "Playwright timeout during page navigation"
                Instant.now(),
                request.getDescription(false)
        );

        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(error);
    }

    @ExceptionHandler(CookieCategorizationException.class)
    public ResponseEntity<ErrorResponse> handleCategorization(CookieCategorizationException ex, WebRequest request) {
        log.error("Cookie categorization failed: {}", ex.getMessage(), ex);

        ErrorResponse error = new ErrorResponse(
                ex.getErrorCode(),
                ex.getUserMessage(),                           // User-friendly
                ex.getDeveloperDetails(),                      // Developer details
                Instant.now(),
                request.getDescription(false)
        );

        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(error);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException ex, WebRequest request) {
        log.warn("Validation failed: {}", ex.getMessage());

        Map<String, String> fieldErrors = new HashMap<>();
        ex.getBindingResult().getAllErrors().forEach(error -> {
            String fieldName = ((FieldError) error).getField();
            String errorMessage = error.getDefaultMessage();
            fieldErrors.put(fieldName, errorMessage);
        });

        String fieldErrorDetails = fieldErrors.entrySet().stream()
                .map(entry -> entry.getKey() + ": " + entry.getValue())
                .collect(Collectors.joining(", "));

        ErrorResponse error = new ErrorResponse(
                ErrorCodes.VALIDATION_ERROR,
                "Please fix the following validation errors",   // User-friendly
                "Validation failed for fields: " + fieldErrorDetails, // Developer details
                Instant.now(),
                request.getDescription(false)
        );

        return ResponseEntity.badRequest().body(error);
    }

    @ExceptionHandler(RestClientException.class)
    public ResponseEntity<ErrorResponse> handleRestClient(RestClientException ex, WebRequest request) {
        log.error("External API call failed: {}", ex.getMessage(), ex);

        ErrorResponse error = new ErrorResponse(
                ErrorCodes.EXTERNAL_SERVICE_ERROR,
                "An external service is temporarily unavailable. Please try again later", // User-friendly
                "RestClient error: " + ex.getMessage(),        // Developer details
                Instant.now(),
                request.getDescription(false)
        );

        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(error);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorResponse> handleIllegalArgument(IllegalArgumentException ex, WebRequest request) {
        log.warn("Invalid argument: {}", ex.getMessage());

        ErrorResponse error = new ErrorResponse(
                ErrorCodes.VALIDATION_ERROR,
                "Please provide valid input parameters",    // User-friendly
                "Invalid argument: " + ex.getMessage(),     // Developer details
                Instant.now(),
                request.getDescription(false)
        );

        return ResponseEntity.badRequest().body(error);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleJsonParseError(HttpMessageNotReadableException ex, WebRequest request) {
        log.warn("JSON parsing failed: {}", ex.getMessage());

        String userFriendlyMessage = "Invalid request format. Please check your JSON syntax.";
        String details = "JSON parsing error: " + ex.getMessage();
        String errorCode = ErrorCodes.VALIDATION_ERROR;

        // Check for duplicate key errors
        if (ex.getCause() instanceof com.fasterxml.jackson.core.JsonParseException) {
            com.fasterxml.jackson.core.JsonParseException jsonEx =
                    (com.fasterxml.jackson.core.JsonParseException) ex.getCause();

            if (jsonEx.getMessage() != null && jsonEx.getMessage().toLowerCase().contains("duplicate")) {
                userFriendlyMessage = "Duplicate keys detected in request. Each field must appear only once.";
                details = "Duplicate JSON key found: " + jsonEx.getMessage();
                errorCode = ErrorCodes.VALIDATION_ERROR;
                log.warn("Duplicate key attack attempt detected: {}", jsonEx.getMessage());
            }
        }
        // Your existing specific error handling
        else if (ex.getMessage().contains("Boolean")) {
            userFriendlyMessage = "Invalid boolean value. Use true or false only.";
        } else if (ex.getMessage().contains("Unrecognized token")) {
            userFriendlyMessage = "Invalid JSON format. Check for typos in field values.";
        }
        // Check for other duplicate key patterns in the main exception message
        else if (ex.getMessage() != null && ex.getMessage().toLowerCase().contains("duplicate")) {
            userFriendlyMessage = "Duplicate keys detected in request. Each field must appear only once.";
            details = "Duplicate JSON key detected in request body";
            log.warn("Duplicate key attack attempt detected: {}", ex.getMessage());
        }

        ErrorResponse error = new ErrorResponse(
                errorCode,
                userFriendlyMessage,
                details,
                Instant.now(),
                request.getDescription(false)
        );

        return ResponseEntity.badRequest().body(error);
    }

    @ExceptionHandler(org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ErrorResponse> handlePathVariableValidation(
            org.springframework.web.method.annotation.MethodArgumentTypeMismatchException ex,
            WebRequest request) {

        log.warn("SECURITY: Path variable validation error - {} for request: {}",
                ex.getMessage(), request.getDescription(false));

        String paramName = ex.getName();
        String paramValue = String.valueOf(ex.getValue());

        String userMessage = "Invalid URL parameter format";
        String developerDetails = String.format(
                "SECURITY: Path variable '%s' has invalid value '%s'. Expected format: UUID string",
                paramName, paramValue
        );

        // Enhanced security detection for transaction ID
        if ("transactionId".equals(paramName)) {
            userMessage = "Invalid transaction ID format. Transaction ID must be a valid UUID.";

            // Log security attempt
            log.warn("SECURITY ALERT: Invalid transaction ID attempt - value: {} from request: {}",
                    paramValue, request.getDescription(false));
        }

        ErrorResponse error = new ErrorResponse(
                ErrorCodes.VALIDATION_ERROR,
                userMessage,
                developerDetails,
                Instant.now(),
                request.getDescription(false)
        );

        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .contentType(MediaType.APPLICATION_JSON)  // FORCE JSON
                .body(error);
    }

    @ExceptionHandler(jakarta.validation.ConstraintViolationException.class)
    public ResponseEntity<ErrorResponse> handleConstraintViolation(
            jakarta.validation.ConstraintViolationException ex,
            WebRequest request) {

        log.warn("Constraint violation: {}", ex.getMessage());

        String violations = ex.getConstraintViolations().stream()
                .map(violation -> violation.getPropertyPath() + ": " + violation.getMessage())
                .collect(Collectors.joining(", "));

        ErrorResponse error = new ErrorResponse(
                ErrorCodes.VALIDATION_ERROR,
                "Request contains invalid parameters",
                "Constraint violations: " + violations,
                Instant.now(),
                request.getDescription(false)
        );

        return ResponseEntity.badRequest().body(error);
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ErrorResponse> handleMethodNotSupported(
            HttpRequestMethodNotSupportedException ex,
            WebRequest request) {

        log.warn("Method not supported: {} for {}", ex.getMethod(), request.getDescription(false));

        // Get supported methods
        String supportedMethods = ex.getSupportedHttpMethods() != null
                ? ex.getSupportedHttpMethods().stream()
                .map(Object::toString)
                .collect(Collectors.joining(", "))
                : "GET, POST";

        ErrorResponse error = new ErrorResponse(
                ErrorCodes.VALIDATION_ERROR, // or create METHOD_NOT_SUPPORTED
                "HTTP method '" + ex.getMethod() + "' is not supported for this endpoint",
                "Supported methods: " + supportedMethods + ". Request: " + ex.getMessage(),
                Instant.now(),
                request.getDescription(false)
        );

        // Set proper Allow header for OPTIONS requests
        HttpHeaders headers = new HttpHeaders();
        if (ex.getSupportedHttpMethods() != null) {
            headers.setAllow(ex.getSupportedHttpMethods());
        }

        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED)
                .headers(headers)
                .body(error);
    }

    // ==================== ENHANCED ERROR HANDLING WITH ATTACK DETECTION ====================

    /**
     * Enhanced exception handling with error tracking and attack detection
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleGeneral(Exception ex, WebRequest request) {
        // Track error patterns to identify potential DoS attacks
        String clientInfo = extractClientInfo(request);
        log.error("Unexpected error from client {}: {}", clientInfo, ex.getMessage(), ex);

        // Check if this might be a resource exhaustion attack
        if (isResourceExhaustionPattern(ex)) {
            log.warn("Potential resource exhaustion attack detected from client: {}", clientInfo);

            ErrorResponse error = new ErrorResponse(
                    "R5036", // Resource Exhaustion Protection
                    "Request could not be processed due to resource constraints. Please try again later.",
                    "Resource exhaustion protection triggered: " + ex.getClass().getSimpleName(),
                    Instant.now(),
                    request.getDescription(false)
            );

            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .header("Retry-After", "120") // Longer retry delay
                    .body(error);
        }

        ErrorResponse error = new ErrorResponse(
                ErrorCodes.INTERNAL_ERROR,
                "Something went wrong on our end. Please try again later", // User-friendly
                "Unexpected error: " + ex.getClass().getSimpleName() + " - " + ex.getMessage(), // Developer details
                Instant.now(),
                request.getDescription(false)
        );

        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(error);
    }

    private String extractClientInfo(WebRequest request) {
        // Extract client IP and User-Agent for tracking
        try {
            HttpServletRequest httpRequest = (HttpServletRequest) request.resolveReference("request");
            if (httpRequest != null) {
                String ip = getClientIpAddress(httpRequest);
                String userAgent = httpRequest.getHeader("User-Agent");
                return ip + " [" + (userAgent != null ? userAgent : "unknown") + "]";
            }
        } catch (Exception e) {
            // Ignore extraction errors
        }
        return "unknown";
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

    private boolean isResourceExhaustionPattern(Exception ex) {
        // Identify patterns that suggest resource exhaustion attacks
        String message = ex.getMessage();
        String className = ex.getClass().getSimpleName();

        return message != null && (
                message.contains("too many open files") ||
                        message.contains("cannot allocate memory") ||
                        message.contains("connection pool exhausted") ||
                        message.contains("thread pool") ||
                        className.contains("OutOfMemory") ||
                        className.contains("StackOverflow") ||
                        className.contains("RejectedExecution")
        );
    }

    @ExceptionHandler(ConsentHandleExpiredException.class)
    public ResponseEntity<ErrorResponse> handleConsentHandleExpired(
            ConsentHandleExpiredException ex,
            HttpServletRequest request) {

        ErrorResponse error = new ErrorResponse(
                ex.getErrorCode(),
                ex.getMessage(),
                ex.getDetails(),
                Instant.now(),
                "uri=" + request.getRequestURI()
        );

        return ResponseEntity.status(HttpStatus.GONE).body(error);
    }
}