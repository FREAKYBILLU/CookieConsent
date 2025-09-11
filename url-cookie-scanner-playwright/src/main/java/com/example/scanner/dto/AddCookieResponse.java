package com.example.scanner.dto;

import lombok.Data;

@Data
public class AddCookieResponse {

    private String transactionId;
    private String name;
    private String domain;
    private String subdomainName;
    private boolean added;
    private String message;

    public AddCookieResponse() {
    }

    public AddCookieResponse(String transactionId, String name, String domain,
                             String subdomainName, boolean added, String message) {
        this.transactionId = transactionId;
        this.name = name;
        this.domain = domain;
        this.subdomainName = subdomainName;
        this.added = added;
        this.message = message;
    }

    // Success response factory method
    public static AddCookieResponse success(String transactionId, String name,
                                            String domain, String subdomainName) {
        return new AddCookieResponse(transactionId, name, domain, subdomainName,
                true, "Cookie added successfully");
    }

    // Failure response factory method
    public static AddCookieResponse failure(String transactionId, String name,
                                            String domain, String subdomainName, String message) {
        return new AddCookieResponse(transactionId, name, domain, subdomainName,
                false, message);
    }

    // Duplicate response factory method
    public static AddCookieResponse duplicate(String transactionId, String name,
                                              String domain, String subdomainName) {
        return new AddCookieResponse(transactionId, name, domain, subdomainName,
                false, "Cookie already exists in this subdomain");
    }
}