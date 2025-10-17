package com.example.scanner.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Request object for updating an existing cookie category")
public class UpdateCookieCategoryRequest {

    @NotBlank(message = "Category is required")
    @Size(min = 2, max = 100, message = "Category must be between 2 and 100 characters")
    @Schema(
            description = "Name of the cookie category to update",
            example = "Analytics",
            required = true,
            minLength = 2,
            maxLength = 100
    )
    private String category;

    @NotBlank(message = "Description is required")
    @Size(min = 5, max = 500, message = "Description must be between 5 and 500 characters")
    @Schema(
            description = "Updated description of the cookie category",
            example = "Updated description for analytics cookies",
            required = true,
            minLength = 5,
            maxLength = 500
    )
    private String description;
}
