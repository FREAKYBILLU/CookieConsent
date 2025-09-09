package com.example.scanner.entity;

import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.util.List;

@Data
@Document(collection = "scan_results")
public class ScanResultEntity {
    @Id
    private String id;
    private String transactionId;
    private String status; // PENDING, RUNNING, COMPLETED, FAILED
    private List<CookieEntity> cookies; // store cookies here
    private String errorMessage;
    private String url;

    public ScanResultEntity() {
    }

}
