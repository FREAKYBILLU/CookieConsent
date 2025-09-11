package com.example.scanner.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;

import java.time.Instant;

@Data
public class ErrorResponse {
    private String errorCode;
    private String message;        // User-friendly message
    private String details;        // Developer-friendly details

    @JsonFormat(shape = JsonFormat.Shape.STRING)
    private Instant timestamp;

    private String path;

    public ErrorResponse() {}

    // Constructor with details
    public ErrorResponse(String errorCode, String message, String details, Instant timestamp, String path) {
        this.errorCode = errorCode;
        this.message = message;
        this.details = details;
        this.timestamp = timestamp;
        this.path = path;
    }

    // Backward compatibility constructor (without details)
    public ErrorResponse(String errorCode, String message, Instant timestamp, String path) {
        this.errorCode = errorCode;
        this.message = message;
        this.details = null; // Will be set separately
        this.timestamp = timestamp;
        this.path = path;
    }
}