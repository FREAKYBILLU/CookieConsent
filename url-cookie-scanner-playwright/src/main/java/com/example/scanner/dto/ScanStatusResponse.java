package com.example.scanner.dto;

import com.example.scanner.entity.CookieEntity;

import java.util.List;

public class ScanStatusResponse {
    private String transactionId;
    private String status; // PENDING, RUNNING, COMPLETED, FAILED
    private List<CookieEntity> cookies; // null if not completed

    public ScanStatusResponse(String transactionId, String status, List<CookieEntity> cookies) {
        this.transactionId = transactionId;
        this.status = status;
        this.cookies = cookies;
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

    public List<CookieEntity> getCookies() {
        return cookies;
    }

    public void setCookies(List<CookieEntity> cookies) {
        this.cookies = cookies;
    }
}
