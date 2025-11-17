package com.example.scanner.controller;

import com.example.scanner.dto.request.AddCookieCategoryRequest;
import com.example.scanner.dto.request.UpdateCookieCategoryRequest;
import com.example.scanner.dto.response.CookieCategoryResponse;
import com.example.scanner.entity.CookieCategory;
import com.example.scanner.service.CategoryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import jakarta.validation.Valid;

import java.util.List;

@RestController
@RequestMapping("/category")
@RequiredArgsConstructor
@Slf4j
@Validated
@Tag(name = "Cookie Category Management", description = "APIs for managing cookie categories")
public class CategoryController {

    private final CategoryService categoryService;

    @PostMapping(
            produces = MediaType.APPLICATION_JSON_VALUE,
            consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(
            summary = "Add a new cookie category",
            description = "Creates a new cookie category for the specified tenant"
    )
    @ApiResponses(value = {
            @ApiResponse(
                    responseCode = "201",
                    description = "Category created successfully",
                    content = @Content(schema = @Schema(implementation = CookieCategoryResponse.class))
            ),
            @ApiResponse(
                    responseCode = "400",
                    description = "Bad request - validation failed or category already exists",
                    content = @Content(schema = @Schema(implementation = CookieCategoryResponse.class))
            ),
            @ApiResponse(
                    responseCode = "500",
                    description = "Internal server error",
                    content = @Content(schema = @Schema(implementation = CookieCategoryResponse.class))
            )
    })
    public ResponseEntity<CookieCategoryResponse> addCategory(
            @Parameter(
                    description = "Tenant ID for multi-tenant support",
                    required = true,
                    example = "tenant-123"
            )
            @RequestHeader(value = "X-Tenant-ID", required = true) String tenantId,
            @Parameter(
                    description = "Cookie category details to be added",
                    required = true
            )
            @Valid @RequestBody AddCookieCategoryRequest request) {

        log.info("Received add category request for tenant: {} with category: {}",
                tenantId, request.getCategory());

        // Validate tenant ID
        if (tenantId == null || tenantId.trim().isEmpty()) {
            log.error("Tenant ID is missing or empty");
            CookieCategoryResponse errorResponse = CookieCategoryResponse.builder()
                    .success(false)
                    .message("Tenant ID is required in header")
                    .build();
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(errorResponse);
        }

        // Process the request
        CookieCategoryResponse response = categoryService.addCategory(request, tenantId);

        // Return appropriate response based on success
        if (response.isSuccess()) {
            return ResponseEntity.status(HttpStatus.CREATED).body(response);
        } else {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(response);
        }
    }

    @PutMapping(
            produces = MediaType.APPLICATION_JSON_VALUE,
            consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(
            summary = "Update an existing cookie category",
            description = "Updates the description of an existing cookie category for the specified tenant"
    )
    @ApiResponses(value = {
            @ApiResponse(
                    responseCode = "200",
                    description = "Category updated successfully",
                    content = @Content(schema = @Schema(implementation = CookieCategoryResponse.class))
            ),
            @ApiResponse(
                    responseCode = "400",
                    description = "Bad request - validation failed",
                    content = @Content(schema = @Schema(implementation = CookieCategoryResponse.class))
            ),
            @ApiResponse(
                    responseCode = "404",
                    description = "Category not found",
                    content = @Content(schema = @Schema(implementation = CookieCategoryResponse.class))
            ),
            @ApiResponse(
                    responseCode = "500",
                    description = "Internal server error",
                    content = @Content(schema = @Schema(implementation = CookieCategoryResponse.class))
            )
    })
    public ResponseEntity<CookieCategoryResponse> updateCookieCategory(
            @Parameter(
                    description = "Tenant ID for multi-tenant support",
                    required = true,
                    example = "tenant-123"
            )
            @RequestHeader(value = "X-Tenant-ID", required = true) String tenantId,
            @Parameter(
                    description = "Cookie category details to be updated",
                    required = true
            )
            @Valid @RequestBody UpdateCookieCategoryRequest request) {

        log.info("Received update category request for tenant: {} with category: {}",
                tenantId, request.getCategory());

        // Validate tenant ID
        if (tenantId == null || tenantId.trim().isEmpty()) {
            log.error("Tenant ID is missing or empty");
            CookieCategoryResponse errorResponse = CookieCategoryResponse.builder()
                    .success(false)
                    .message("Tenant ID is required in header")
                    .build();
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(errorResponse);
        }

        // Process the request
        CookieCategoryResponse response = categoryService.updateCookieCategory(request, tenantId);
        return ResponseEntity.ok(response);
    }

    @GetMapping()
    @Operation(
            summary = "Fetch all cookie categories",
            description = "Retrieves all cookie categories for the specified tenant"
    )
    @ApiResponses(value = {
            @ApiResponse(
                    responseCode = "200",
                    description = "Categories fetched successfully",
                    content = @Content(array = @ArraySchema(schema = @Schema(implementation = CookieCategory.class)))
            ),
            @ApiResponse(
                    responseCode = "404",
                    description = "No categories found for the given tenant",
                    content = @Content
            ),
            @ApiResponse(
                    responseCode = "500",
                    description = "Internal server error",
                    content = @Content
            )
    })
    public ResponseEntity<List<CookieCategoryResponse>> getAllCategory(
            @Parameter(
                    description = "Tenant ID for multi-tenant support",
                    required = true,
                    example = "tenant-123"
            )
            @RequestHeader(value = "X-Tenant-ID", required = true) String tenantId){
        List<CookieCategoryResponse> categoryList = categoryService.findAll(tenantId);
        if(categoryList.isEmpty()){
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(categoryList);
        }
        return ResponseEntity.status(HttpStatus.OK).body(categoryList);
    }

}