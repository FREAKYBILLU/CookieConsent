package com.example.scanner.config;

import com.example.scanner.constants.ErrorCodes;
import com.example.scanner.dto.ErrorResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.Instant;
import java.util.regex.Pattern;

@Component
@Order(1)
public class InvalidCharacterFilter implements Filter {

    private static final Logger log = LoggerFactory.getLogger(InvalidCharacterFilter.class);

    // Pattern for invalid characters that might cause Tomcat to reject
    private static final Pattern INVALID_CHAR_PATTERN = Pattern.compile("[\\$%\\^&\\*'\"<>\\\\]");

    private final ObjectMapper objectMapper;

    public InvalidCharacterFilter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {

        if (request instanceof HttpServletRequest && response instanceof HttpServletResponse) {
            HttpServletRequest httpRequest = (HttpServletRequest) request;
            HttpServletResponse httpResponse = (HttpServletResponse) response;

            String requestURI = httpRequest.getRequestURI();

            // Check if URI contains invalid characters
            if (INVALID_CHAR_PATTERN.matcher(requestURI).find()) {
                log.warn("Blocking request with invalid characters: {}", requestURI);
                handleInvalidCharacters(httpRequest, httpResponse, requestURI);
                return;
            }
        }

        chain.doFilter(request, response);
    }

    private void handleInvalidCharacters(HttpServletRequest request, HttpServletResponse response, String uri)
            throws IOException {

        ErrorResponse errorResponse = new ErrorResponse(
                ErrorCodes.VALIDATION_ERROR,
                "Invalid characters in URL path",
                String.format("Request URI '%s' contains invalid characters. Only alphanumeric, hyphen, and underscore are allowed.", uri),
                Instant.now(),
                uri
        );

        response.setStatus(HttpStatus.BAD_REQUEST.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");

        String jsonResponse = objectMapper.writeValueAsString(errorResponse);
        response.getWriter().write(jsonResponse);
        response.getWriter().flush();
    }
}