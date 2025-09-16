package com.example.scanner.controller;

import com.example.scanner.constants.ErrorCodes;
import com.example.scanner.dto.ErrorResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.web.servlet.error.ErrorController;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;

@RestController
public class CustomErrorController implements ErrorController {

    private static final Logger log = LoggerFactory.getLogger(CustomErrorController.class);

    @RequestMapping("/error")
    public ResponseEntity<ErrorResponse> handleError(HttpServletRequest request) {
        Object status = request.getAttribute(RequestDispatcher.ERROR_STATUS_CODE);
        Object message = request.getAttribute(RequestDispatcher.ERROR_MESSAGE);
        Object requestUri = request.getAttribute(RequestDispatcher.ERROR_REQUEST_URI);
        Object exception = request.getAttribute(RequestDispatcher.ERROR_EXCEPTION);

        log.info("CustomErrorController triggered - Status: {}, URI: {}, Message: {}",
                status, requestUri, message);

        String userMessage = "Invalid request format";
        String developerDetails = "Request processing failed";
        HttpStatus httpStatus = HttpStatus.BAD_REQUEST;

        if (status != null) {
            int statusCode = Integer.parseInt(status.toString());

            if (statusCode == HttpStatus.BAD_REQUEST.value()) {
                userMessage = "Invalid request format or characters in URL";
                developerDetails = String.format(
                        "Bad request for URI: %s. Error: %s. Exception: %s",
                        requestUri != null ? requestUri.toString() : "unknown",
                        message != null ? message.toString() : "Invalid characters in URL",
                        exception != null ? exception.toString() : "none"
                );
                httpStatus = HttpStatus.BAD_REQUEST;
            } else if (statusCode == HttpStatus.NOT_FOUND.value()) {
                userMessage = "The requested endpoint was not found";
                developerDetails = String.format("Not found: %s", requestUri);
                httpStatus = HttpStatus.NOT_FOUND;
            } else {
                httpStatus = HttpStatus.valueOf(statusCode);
                developerDetails = String.format("HTTP %d error for URI: %s", statusCode, requestUri);
            }
        }

        ErrorResponse errorResponse = new ErrorResponse(
                ErrorCodes.VALIDATION_ERROR,
                userMessage,
                developerDetails,
                Instant.now(),
                request.getRequestURI()
        );

        return ResponseEntity.status(httpStatus).body(errorResponse);
    }
}