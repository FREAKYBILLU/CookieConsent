package com.example.scanner.controller;

import com.example.scanner.dto.request.CreateConsentRequest;
import com.example.scanner.dto.response.ConsentCreateResponse;
import com.example.scanner.service.ConsentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.parameters.RequestBody;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@CrossOrigin(origins = "*")
@RestController
@RequestMapping("/v1.0/consent")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "Consent Management System", description = "Operations pertaining to consents")
public class CreateConsentController {

    private final ConsentService consentService;

    @PostMapping("/create")
    @Operation(summary = "Create a consent by consent handle ID",
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
                    @ApiResponse(responseCode = "201", description = "Consent created successfully",
                            content = @Content(schema = @Schema(implementation = ConsentCreateResponse.class))),
                    @ApiResponse(responseCode = "400", description = "Invalid request or consent handle not found"),
                    @ApiResponse(responseCode = "409", description = "Consent handle already used or expired"),
                    @ApiResponse(responseCode = "500", description = "Internal server error")
            }
    )
    public ResponseEntity<ConsentCreateResponse> createConsentByConsentHandleId(
            @RequestHeader("X-Tenant-ID") String tenantId,
            @org.springframework.web.bind.annotation.RequestBody CreateConsentRequest request,
            @RequestHeader Map<String, String> headers) throws Exception {

        log.info("Creating consent for handle: {}, tenant: {}", request.getConsentHandleId(), tenantId);

        ConsentCreateResponse response = this.consentService.createConsentByConsentHandleId(request, tenantId);

        log.info("Successfully created consent: {} for handle: {}", response.getConsentId(), request.getConsentHandleId());

        return new ResponseEntity<>(response, HttpStatus.CREATED);
    }
}