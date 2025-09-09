package com.example.scanner.dto;

import lombok.Data;

import java.util.List;


@Data
public class ScanResultDto {
    private String id;
    private String transactionId;
    private String status; // PENDING, RUNNING, COMPLETED, FAILED
    private List<CookieDto> cookies; // Changed from CookieEntity to CookieDto
    private String errorMessage;
    private String url;

    public ScanResultDto() {
    }

}