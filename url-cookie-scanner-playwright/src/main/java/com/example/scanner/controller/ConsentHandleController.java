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
@Tag(name = "Consent Handle Management System", description = "Operations pertaining to consent handles for secure cookie consent flow")
public class ConsentHandleController {

    private final ConsentHandleService consentHandleService;

    @PostMapping("/create")
    @Operation(
            summary = "Create a new consent handle (SecureCode API)",
            description = "Generates a secure, time-limited code (consent handle) for cookie consent operations. " +
                    "This is the first step in the consent flow - equivalent to InitCreateConsent API.",
            requestBody = @RequestBody(
                    description = "Request body for creating a consent handle",
                    required = true,
                    content = @Content(schema = @Schema(implementation = CreateHandleRequest.class))
            ),
            parameters = {
                    @Parameter(name = "txn", description = "Transaction ID (UUID)", required = true,
                            example = "a1b2c3d4-e5f6-7890-1234-567890abcdef"),
                    @Parameter(name = "X-Tenant-ID", description = "Tenant ID (UUID)", required = true,
                            example = "a1b2c3d4-e5f6-7890-1234-567890abcdef"),
                    @Parameter(name = "business-id", description = "Business ID (UUID)", required = true,
                            example = "b1c2d3e4-f5g6-7890-1234-567890abcdef")
            },
            responses = {
                    @ApiResponse(
                            responseCode = "201",
                            description = "Consent Handle created successfully",
                            content = @Content(schema = @Schema(implementation = ConsentHandleResponse.class))
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

        log.info("Creating consent handle for templateId: {}, customer: {}, tenant: {}",
                request.getTemplateId(), request.getCustomerIdentifiers().getValue(), tenantId);

        ConsentHandleResponse response = this.consentHandleService.createConsentHandle(tenantId, request, headers);

        log.info("Successfully {} consent handle: {} for transaction: {} and tenant: {}",
                response.isNewHandle() ? "created" : "returned existing",
                response.getConsentHandleId(),
                headers.get(Constants.TXN_ID),
                tenantId);

        HttpStatus status = response.isNewHandle() ? HttpStatus.CREATED : HttpStatus.OK;
        return new ResponseEntity<>(response, status);
    }

    @GetMapping("/get/{consentHandleId}")
    @Operation(
            summary = "Get consent handle by ID (GetCookieConsentTemplate API)",
            description = "Retrieves consent handle details and associated template information using the secure code. " +
                    "Returns cookie report data, popup theme, and language settings.",
            parameters = {
                    @Parameter(name = "consentHandleId", description = "Consent Handle ID (Secure Code)",
                            required = true, example = "550e8400-e29b-41d4-a716-446655440000"),
                    @Parameter(name = "txn", description = "Transaction ID (UUID)", required = true,
                            example = "a1b2c3d4-e5f6-7890-1234-567890abcdef"),
                    @Parameter(name = "business-id", description = "Business ID (UUID)", required = true,
                            example = "b1c2d3e4-f5g6-7890-1234-567890abcdef"),
                    @Parameter(name = "X-Tenant-ID", description = "Tenant ID (UUID)", required = true,
                            example = "a1b2c3d4-e5f6-7890-1234-567890abcdef")
            },
            responses = {
                    @ApiResponse(
                            responseCode = "200",
                            description = "Consent Handle retrieved successfully",
                            content = @Content(schema = @Schema(implementation = GetHandleResponse.class))
                    ),
                    @ApiResponse(
                            responseCode = "400",
                            description = "Invalid consent handle ID",
                            content = @Content(schema = @Schema(implementation = ErrorResponse.class))
                    ),
                    @ApiResponse(
                            responseCode = "404",
                            description = "Consent Handle not found or expired",
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

        log.info("Retrieving consent handle: {} for tenant: {}", consentHandleId, tenantId);

        GetHandleResponse response = this.consentHandleService.getConsentHandleById(consentHandleId, tenantId);

        log.info("Successfully retrieved consent handle: {} for tenant: {}", consentHandleId, tenantId);

        return new ResponseEntity<>(response, HttpStatus.OK);
    }
}