package com.example.scanner.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.List;

@Data
public class ScanRequestDto {

    @NotBlank(message = "URL is required and cannot be empty")
    private String url;

    private List<String> subDomain;

    public ScanRequestDto() {
    }

    public ScanRequestDto(String url, List<String> subDomain) {
        this.url = url;
        this.subDomain = subDomain;
    }
}