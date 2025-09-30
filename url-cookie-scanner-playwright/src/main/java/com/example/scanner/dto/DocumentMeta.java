package com.example.scanner.dto;


import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class DocumentMeta {

    @Schema(
            description = "Unique document identifier",
            example = "doc_123e4567-e89b-12d3-a456-426614174000"
    )
    private String documentId;


    @Schema(
            description = "Original filename",
            example = "privacy-policy.pdf"
    )
    private String name;

    @Schema(
            description = "MIME type of the document",
            example = "application/pdf"
    )
    private String contentType;

    @Schema(
            description = "File size in bytes",
            example = "327467"
    )
    private Long size;

    @Schema(
            description = "Document classification tags",
            implementation = Tag.class
    )
    private Tag tag;
}