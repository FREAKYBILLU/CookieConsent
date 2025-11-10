package com.example.scanner.controller;

import com.example.scanner.dto.request.CreateConsentRequest;
import com.example.scanner.dto.request.UpdateConsentRequest;
import com.example.scanner.dto.response.*;
import com.example.scanner.entity.Consent;
import com.example.scanner.service.ConsentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.parameters.RequestBody;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@CrossOrigin(origins = "*")
@RestController
@RequestMapping("/consent")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "Consent Management System", description = "Complete consent lifecycle operations including creation, updates, and history")
public class ConsentController {

    private final ConsentService consentService;

    @PostMapping("/create")
    @Operation(
            summary = "Create a consent by consent handle ID",
            description = "Creates a consent record based on user's preference choices (Accept All, Accept Necessary, Manage Preferences). " +
                    "This is the third step in the consent flow - CreateConsent API equivalent.",
            requestBody = @RequestBody(description = "Request body for creating consent", required = true,
                    content = @Content(schema = @Schema(implementation = CreateConsentRequest.class))),
            parameters = {
                    @Parameter(name = "txn", description = "Transaction ID (UUID)", required = true,
                            example = "a1b2c3d4-e5f6-7890-1234-567890abcdef"),
                    @Parameter(name = "X-Tenant-ID", description = "Tenant ID (UUID)", required = true,
                            example = "a1b2c3d4-e5f6-7890-1234-567890abcdef")
            },
            responses = {
                    @ApiResponse(
                            responseCode = "201",
                            description = "Consent created successfully",
                            content = @Content(schema = @Schema(implementation = ConsentCreateResponse.class))
                    ),
                    @ApiResponse(
                            responseCode = "400",
                            description = "Invalid request or consent handle not found",
                            content = @Content(schema = @Schema(implementation = ErrorResponse.class))
                    ),
                    @ApiResponse(
                            responseCode = "409",
                            description = "Consent handle already used or expired",
                            content = @Content(schema = @Schema(implementation = ErrorResponse.class))
                    ),
                    @ApiResponse(
                            responseCode = "500",
                            description = "Internal server error",
                            content = @Content(schema = @Schema(implementation = ErrorResponse.class))
                    )
            }
    )
    public ResponseEntity<ConsentCreateResponse> createConsentByConsentHandleId(
            @RequestHeader("X-Tenant-ID") String tenantId,
            @org.springframework.web.bind.annotation.RequestBody @Valid CreateConsentRequest request,
            @RequestHeader Map<String, String> headers) throws Exception {

        log.info("Creating consent for handle: {}, tenant: {}", request.getConsentHandleId(), tenantId);

        ConsentCreateResponse response = consentService.createConsentByConsentHandleId(request, tenantId);

        log.info("Successfully created consent: {} for handle: {}", response.getConsentId(), request.getConsentHandleId());

        return new ResponseEntity<>(response, HttpStatus.CREATED);
    }

    @PutMapping("/{consentId}/update")
    @Operation(
            summary = "Update a consent (creates new version)",
            description = "Creates a new version of an existing consent with user's updated preference choices. " +
                    "Requires a valid consent handle for security. The consentId remains the same, " +
                    "but a new document with incremented version number is created. Previous version is marked as 'UPDATED'.",
            parameters = {
                    @Parameter(name = "X-Tenant-ID", description = "Tenant ID", required = true,
                            example = "tenant_123e4567-e89b-12d3-a456-426614174000"),
                    @Parameter(name = "consentId", description = "Logical Consent ID (not document ID)", required = true,
                            example = "cst_123e4567-e89b-12d3-a456-426614174000")
            },
            responses = {
                    @ApiResponse(
                            responseCode = "200",
                            description = "Consent updated successfully (new version created)",
                            content = @Content(schema = @Schema(implementation = UpdateConsentResponse.class))
                    ),
                    @ApiResponse(
                            responseCode = "400",
                            description = "Invalid request or consent not found",
                            content = @Content(schema = @Schema(implementation = ErrorResponse.class))
                    ),
                    @ApiResponse(
                            responseCode = "409",
                            description = "Consent handle already used or expired",
                            content = @Content(schema = @Schema(implementation = ErrorResponse.class))
                    ),
                    @ApiResponse(
                            responseCode = "422",
                            description = "Consent cannot be updated (e.g., expired, wrong customer)",
                            content = @Content(schema = @Schema(implementation = ErrorResponse.class))
                    ),
                    @ApiResponse(
                            responseCode = "500",
                            description = "Internal server error",
                            content = @Content(schema = @Schema(implementation = ErrorResponse.class))
                    )
            }
    )
    public ResponseEntity<UpdateConsentResponse> updateConsent(
            @RequestHeader("X-Tenant-ID") String tenantId,
            @PathVariable String consentId,
            @org.springframework.web.bind.annotation.RequestBody @Valid UpdateConsentRequest updateRequest) throws Exception {

        log.info("Received consent update request for consentId: {} in tenant: {}", consentId, tenantId);

        UpdateConsentResponse response = consentService.updateConsent(consentId, updateRequest, tenantId);

        log.info("Successfully updated consent: {} to version: {}", consentId, response.getNewVersion());

        return ResponseEntity.ok(response);
    }

    @GetMapping("/{consentId}/history")
    @Operation(
            summary = "Get consent version history",
            description = "Retrieves all versions of a consent ordered by version number (latest first). " +
                    "Shows complete audit trail of consent changes and user preference updates.",
            parameters = {
                    @Parameter(name = "X-Tenant-ID", description = "Tenant ID", required = true),
                    @Parameter(name = "consentId", description = "Logical Consent ID", required = true),
                    @Parameter(name = "business-id", description = "Business ID (UUID)", required = true,
                            example = "b1c2d3e4-f5g6-7890-1234-567890abcdef")
            },
            responses = {
                    @ApiResponse(
                            responseCode = "200",
                            description = "Consent history retrieved successfully",
                            content = @Content(schema = @Schema(implementation = Consent.class))
                    ),
                    @ApiResponse(
                            responseCode = "400",
                            description = "Invalid consent ID",
                            content = @Content(schema = @Schema(implementation = ErrorResponse.class))
                    ),
                    @ApiResponse(
                            responseCode = "404",
                            description = "Consent not found",
                            content = @Content(schema = @Schema(implementation = ErrorResponse.class))
                    ),
                    @ApiResponse(
                            responseCode = "500",
                            description = "Internal server error",
                            content = @Content(schema = @Schema(implementation = ErrorResponse.class))
                    )
            }
    )
    public ResponseEntity<List<Consent>> getConsentHistory(
            @RequestHeader("X-Tenant-ID") String tenantId,
            @PathVariable String consentId) throws Exception {

        log.info("Retrieving consent history for consentId: {} in tenant: {}", consentId, tenantId);

        List<Consent> history = consentService.getConsentHistory(consentId, tenantId);

        log.info("Retrieved {} versions for consent: {}", history.size(), consentId);

        return ResponseEntity.ok(history);
    }

    @GetMapping("/{consentId}/versions/{version}")
    @Operation(
            summary = "Get specific consent version",
            description = "Retrieves a specific version of a consent. Useful for viewing historical consent configurations " +
                    "and user preference choices at specific points in time.",
            parameters = {
                    @Parameter(name = "X-Tenant-ID", description = "Tenant ID", required = true),
                    @Parameter(name = "consentId", description = "Logical Consent ID", required = true),
                    @Parameter(name = "version", description = "Version number", required = true, example = "2")
            },
            responses = {
                    @ApiResponse(
                            responseCode = "200",
                            description = "Consent version retrieved successfully",
                            content = @Content(schema = @Schema(implementation = Consent.class))
                    ),
                    @ApiResponse(
                            responseCode = "400",
                            description = "Invalid consent ID or version",
                            content = @Content(schema = @Schema(implementation = ErrorResponse.class))
                    ),
                    @ApiResponse(
                            responseCode = "404",
                            description = "Consent version not found",
                            content = @Content(schema = @Schema(implementation = ErrorResponse.class))
                    ),
                    @ApiResponse(
                            responseCode = "500",
                            description = "Internal server error",
                            content = @Content(schema = @Schema(implementation = ErrorResponse.class))
                    )
            }
    )
    public ResponseEntity<Consent> getConsentVersion(
            @RequestHeader("X-Tenant-ID") String tenantId,
            @PathVariable String consentId,
            @PathVariable Integer version) throws Exception {

        log.info("Retrieving consent version {} for consentId: {} in tenant: {}", version, consentId, tenantId);

        Optional<Consent> consentOpt = consentService.getConsentByIdAndVersion(tenantId, consentId, version);

        if (consentOpt.isEmpty()) {
            log.warn("Consent version not found: consentId={}, version={}, tenant={}", consentId, version, tenantId);
            // This will be handled by GlobalExceptionHandler
            throw new IllegalArgumentException("No consent found with ID '" + consentId + "' and version " + version);
        }

        log.info("Retrieved consent version {} for consentId: {}", version, consentId);

        return ResponseEntity.ok(consentOpt.get());
    }

    @GetMapping("/validate-token")
    @Operation(
            summary = "Validate a consent token",
            parameters = {
                    @Parameter(name = "consent-token", description = "Consent Token", required = true, example = "eyJhbGciOiJIUzI1NiJ9..."),
                    @Parameter(name = "txn", description = "Transaction ID (UUID)", required = true, example = "a1b2c3d4-e5f6-7890-1234-567890abcdef"),
                    @Parameter(name = "X-Tenant-ID", description = "Tenant ID (UUID)", required = true, example = "a1b2c3d4-e5f6-7890-1234-567890abcdef")
            },
            responses = {
                    @ApiResponse(
                            responseCode = "200",
                            description = "Consent token validated successfully",
                            content = @Content(schema = @Schema(implementation = ConsentTokenValidateResponse.class))
                    ),
                    @ApiResponse(
                            responseCode = "404",
                            description = "Consent token not found",
                            content = @Content(schema = @Schema(implementation = ErrorResponse.class))
                    ),
                    @ApiResponse(
                            responseCode = "500",
                            description = "Internal server error",
                            content = @Content(schema = @Schema(implementation = ErrorResponse.class))
                    )
            }
    )
    public ResponseEntity<ConsentTokenValidateResponse> validateConsent(
            @RequestHeader("consent-token") String consentToken,
            @RequestHeader("X-Tenant-ID") String tenantId) throws Exception {

        return new ResponseEntity<>(consentService.validateConsentToken(consentToken, tenantId), HttpStatus.OK);
    }

    @GetMapping("/check")
    @Operation(
            summary = "Check consent status by deviceId + URL or consentId",
            description = "Returns consent status (PENDING, REQ_EXPIRED, USED, REJECTED, ACTIVE, REVOKE, EXPIRED, No_Record) and consent handle ID",
            parameters = {
                    @Parameter(
                            name = "deviceId",
                            description = "Device identifier from customer identifiers",
                            required = true,
                            example = "92342834928359235"
                    ),
                    @Parameter(
                            name = "url",
                            description = "Website URL",
                            required = true,
                            example = "http://www.example.com"
                    ),
                    @Parameter(
                            name = "consentId",
                            description = "Consent ID (if provided, deviceId and URL are ignored)",
                            required = false,
                            example = "a1b2c3d4-e5f6-7890-1234-567890abcdef"
                    ),
                    @Parameter(
                            name = "X-Tenant-ID",
                            description = "Tenant ID (UUID)",
                            required = true,
                            in = ParameterIn.HEADER,
                            example = "550e8400-e29b-41d4-a716-446655440000"
                    )
            },
            responses = {
                    @ApiResponse(
                            responseCode = "200",
                            description = "Consent status retrieved successfully",
                            content = @Content(
                                    mediaType = "application/json",
                                    schema = @Schema(implementation = CheckConsentResponse.class),
                                    examples = @ExampleObject(
                                            name = "Success Response",
                                            value = "{\"consentStatus\": \"ACCEPTED\", \"consentHandleId\": \"9bb14c63-7ec8-47f5-86b5-4a8c848012c1\"}"
                                    )
                            )
                    ),
                    @ApiResponse(
                            responseCode = "400",
                            description = "Invalid request - Missing required parameters",
                            content = @Content(
                                    mediaType = "application/json",
                                    examples = @ExampleObject(
                                            value = "{\"errorCode\": \"R4001\", \"message\": \"Device ID is required\"}"
                                    )
                            )
                    ),
                    @ApiResponse(
                            responseCode = "500",
                            description = "Internal server error",
                            content = @Content(
                                    mediaType = "application/json",
                                    examples = @ExampleObject(
                                            value = "{\"errorCode\": \"R5001\", \"message\": \"Failed to retrieve consent status\"}"
                                    )
                            )
                    )
            }
    )
    public ResponseEntity<CheckConsentResponse> checkConsent(
            @RequestHeader("X-Tenant-ID") String tenantId,
            @RequestParam(value = "deviceId", required = true) String deviceId,
            @RequestParam(value = "url", required = true) String url,
            @RequestParam(value = "consentId", required = false) String consentId) throws Exception {
        return new ResponseEntity<>(consentService.getConsentStatus(deviceId, url, consentId, tenantId), HttpStatus.OK);
    }
}