package com.example.scanner.dto;

import java.util.List;

public class ScanResultDto {
    private String id;
    private String transactionId;
    private String status; // PENDING, RUNNING, COMPLETED, FAILED
    private List<CookieDto> cookies; // Changed from CookieEntity to CookieDto
    private String errorMessage;
    private String url;

    public ScanResultDto() {
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getTransactionId() {
        return transactionId;
    }

    public void setTransactionId(String transactionId) {
        this.transactionId = transactionId;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public List<CookieDto> getCookies() {
        return cookies;
    }

    public void setCookies(List<CookieDto> cookies) {
        this.cookies = cookies;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }
}