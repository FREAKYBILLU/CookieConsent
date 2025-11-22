package com.example.scanner.controller;

import com.example.scanner.constants.Constants;
import com.example.scanner.dto.response.ConsentHandleResponse;
import com.example.scanner.dto.request.CreateHandleRequest;
import com.example.scanner.dto.response.ErrorResponse;
import com.example.scanner.dto.response.GetHandleResponse;
import com.example.scanner.exception.ScannerException;
import com.example.scanner.service.ConsentHandleService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.parameters.RequestBody;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/consent-handle")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "Consent Handle Management", description = "Secure consent handle operations for cookie consent flow")
public class ConsentHandleController {

    private final ConsentHandleService consentHandleService;

    @PostMapping("/create")
    @Operation(
            summary = "Create a new consent handle",
            description = """
                Generates a secure, time-limited consent handle (15 min expiry as default).
                First step in consent flow: Create Handle → Get Handle → Create Consent
                
                Error Codes: R4001 (Validation), R4091 (Handle exists), R5000 (Internal)
                """,
            requestBody = @RequestBody(
                    content = @Content(
                            schema = @Schema(implementation = CreateHandleRequest.class),
                            examples = @ExampleObject(value = """
                                {
                                  "templateId": "tpl_123",
                                  "templateVersion": 1,
                                  "url": "https://example.com",
                                  "customerIdentifiers": {"type": "DEVICE_ID", "value": "device123"}
                                }
                                """)
                    )
            ),
            parameters = {
                    @Parameter(name = "txn", description = "Transaction ID", example = "a1b2c3d4-e5f6-7890-1234-567890abcdef"),
                    @Parameter(name = "X-Tenant-ID", description = "Tenant ID", example = "a1b2c3d4-e5f6-7890-1234-567890abcdef"),
                    @Parameter(name = "business-id", description = "Business ID", example = "b1c2d3e4-f5g6-7890-1234-567890abcdef")
            },
            responses = {
                    @ApiResponse(
                            responseCode = "201",
                            description = "Consent handle created successfully",
                            content = @Content(
                                    schema = @Schema(implementation = ConsentHandleResponse.class),
                                    examples = @ExampleObject(value = """
                                        {"consentHandleId": "9bb14c63-7ec8-47f5-86b5-4a8c848012c1", "message": "Consent Handle Created successfully!", "isNewHandle": true}
                                        """)
                            )
                    ),
                    @ApiResponse(
                            responseCode = "400",
                            description = "Invalid request or validation failed",
                            content = @Content(schema = @Schema(implementation = ErrorResponse.class))
                    ),
                    @ApiResponse(
                            responseCode = "409",
                            description = "Consent handle already exists",
                            content = @Content(schema = @Schema(implementation = ErrorResponse.class))
                    ),
                    @ApiResponse(
                            responseCode = "500",
                            description = "Internal server error",
                            content = @Content(schema = @Schema(implementation = ErrorResponse.class))
                    )
            }
    )
    public ResponseEntity<ConsentHandleResponse> createConsentHandle(
            @RequestHeader("X-Tenant-ID") String tenantId,
            @Valid @org.springframework.web.bind.annotation.RequestBody CreateHandleRequest request,
            @RequestHeader Map<String, String> headers) throws ScannerException {
        ConsentHandleResponse response = this.consentHandleService.createConsentHandle(tenantId, request, headers);
        HttpStatus status = response.isNewHandle() ? HttpStatus.CREATED : HttpStatus.OK;
        return new ResponseEntity<>(response, status);
    }

    @GetMapping("/get/{consentHandleId}")
    @Operation(
            summary = "Get consent handle by ID",
            description = """
                Retrieves consent handle details with template and cookie information.
                Returns cookie report, UI config, and preferences with cookies.
                
                Error Codes: R4001 (Invalid ID), R4041 (Not found), R4101 (Expired), R5000 (Internal)
                """,
            parameters = {
                    @Parameter(name = "consentHandleId", description = "Consent Handle ID", example = "9bb14c63-7ec8-47f5-86b5-4a8c848012c1"),
                    @Parameter(name = "txn", description = "Transaction ID", example = "a1b2c3d4-e5f6-7890-1234-567890abcdef"),
                    @Parameter(name = "business-id", description = "Business ID", example = "b1c2d3e4-f5g6-7890-1234-567890abcdef"),
                    @Parameter(name = "X-Tenant-ID", description = "Tenant ID", example = "a1b2c3d4-e5f6-7890-1234-567890abcdef")
            },
            responses = {
                    @ApiResponse(
                            responseCode = "200",
                            description = "Consent handle retrieved successfully",
                            content = @Content(schema = @Schema(implementation = GetHandleResponse.class))
                    ),
                    @ApiResponse(
                            responseCode = "400",
                            description = "Invalid consent handle ID",
                            content = @Content(schema = @Schema(implementation = ErrorResponse.class))
                    ),
                    @ApiResponse(
                            responseCode = "404",
                            description = "Consent handle not found or expired",
                            content = @Content(schema = @Schema(implementation = ErrorResponse.class))
                    ),
                    @ApiResponse(
                            responseCode = "500",
                            description = "Internal server error",
                            content = @Content(schema = @Schema(implementation = ErrorResponse.class))
                    )
            }
    )
    public ResponseEntity<GetHandleResponse> getHandleById(
            @RequestHeader("X-Tenant-ID") String tenantId,
            @PathVariable("consentHandleId") String consentHandleId,
            @RequestHeader Map<String, String> headers) throws ScannerException {
        GetHandleResponse response = this.consentHandleService.getConsentHandleById(consentHandleId, tenantId);
        return new ResponseEntity<>(response, HttpStatus.OK);
    }
}