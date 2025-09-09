package com.example.scanner.dto;

import com.example.scanner.entity.CookieEntity;
import lombok.Data;

import java.util.List;

@Data
public class ScanStatusResponse {
    private String transactionId;
    private String status; // PENDING, RUNNING, COMPLETED, FAILED
    private List<CookieEntity> cookies; // null if not completed

    public ScanStatusResponse(String transactionId, String status, List<CookieEntity> cookies) {
        this.transactionId = transactionId;
        this.status = status;
        this.cookies = cookies;
    }

}
