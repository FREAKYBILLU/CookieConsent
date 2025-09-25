package com.example.scanner.dto.response;

import lombok.Data;

@Data
public class TemplateResponse {
    private String templateId;
    private String message;

    public TemplateResponse(String templateId, String message) {
        this.templateId = templateId;
        this.message = message;
    }
}
