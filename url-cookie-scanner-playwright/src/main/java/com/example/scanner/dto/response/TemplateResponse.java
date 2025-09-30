package com.example.scanner.dto.response;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
@Schema(description = "Response after successfully creating a template")
public class TemplateResponse {

    @Schema(
            description = "Logical template ID (remains same across versions)",
            example = "tpl_123e4567-e89b-12d3-a456-426614174000"
    )
    private String templateId;
    private String message;

    public TemplateResponse(String templateId, String message) {
        this.templateId = templateId;
        this.message = message;
    }
}
