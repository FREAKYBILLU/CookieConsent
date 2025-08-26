package com.example.scanner.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import java.time.Instant;

public class ErrorResponse {
    private String errorCode;
    private String message;
    private String details;

    @JsonFormat(shape = JsonFormat.Shape.STRING)
    private Instant timestamp;

    private String path;

    public ErrorResponse() {}

    public ErrorResponse(String errorCode, String message, String details, Instant timestamp, String path) {
        this.errorCode = errorCode;
        this.message = message;
        this.details = details;
        this.timestamp = timestamp;
        this.path = path;
    }

    // Getters and setters
    public String getErrorCode() {
        return errorCode;
    }

    public void setErrorCode(String errorCode) {
        this.errorCode = errorCode;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public String getDetails() {
        return details;
    }

    public void setDetails(String details) {
        this.details = details;
    }

    public Instant getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(Instant timestamp) {
        this.timestamp = timestamp;
    }

    public String getPath() {
        return path;
    }

    public void setPath(String path) {
        this.path = path;
    }
}