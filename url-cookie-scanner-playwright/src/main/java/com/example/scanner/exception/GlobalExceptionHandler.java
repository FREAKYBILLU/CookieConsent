package com.example.scanner.exception;

import com.example.scanner.constants.ErrorCodes;
import com.example.scanner.dto.ErrorResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.client.RestClientException;
import org.springframework.web.context.request.WebRequest;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(UrlValidationException.class)
    public ResponseEntity<ErrorResponse> handleUrlValidation(UrlValidationException ex, WebRequest request) {
        log.warn("URL validation failed: {}", ex.getMessage());

        ErrorResponse error = new ErrorResponse(
                ex.getErrorCode(),
                "Please provide a valid URL and try again",
                Instant.now(),
                request.getDescription(false)
        );

        return ResponseEntity.badRequest().body(error);
    }

    @ExceptionHandler(CookieNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleCookieNotFoundException(CookieNotFoundException ex, HttpServletRequest request) {
        ErrorResponse errorResponse = new ErrorResponse(
                ex.getErrorCode(),
                "The requested cookie was not found in this transaction",
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
                "The scan transaction you're looking for doesn't exist",
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
                "Unable to complete the scan. Please try again later",
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
                "Cookie categorization is temporarily unavailable. Your scan will continue without categorization",
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

        ErrorResponse error = new ErrorResponse(
                ErrorCodes.VALIDATION_ERROR,
                "Please check your request data and fix the validation errors",
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
                "An external service is temporarily unavailable. Please try again later",
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
                "Please provide valid input parameters",
                Instant.now(),
                request.getDescription(false)
        );

        return ResponseEntity.badRequest().body(error);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleGeneral(Exception ex, WebRequest request) {
        log.error("Unexpected error: {}", ex.getMessage(), ex);

        ErrorResponse error = new ErrorResponse(
                ErrorCodes.INTERNAL_ERROR,
                "Something went wrong on our end. Please try again later",
                Instant.now(),
                request.getDescription(false)
        );

        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(error);
    }
}