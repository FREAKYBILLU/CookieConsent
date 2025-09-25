package com.example.scanner.controller;

import com.example.scanner.dto.request.CreateConsentRequest;
import com.example.scanner.dto.response.ConsentCreateResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.parameters.RequestBody;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@CrossOrigin(origins = "*")
@RestController
@RequestMapping("/v1.0/consent")
@Tag(name = "Consent Management System", description = "Operations pertaining to consents")
public class CreateConsentController {

    @PostMapping("/create")
    @Operation(summary = "Create a consent by consent handle ID",
            requestBody = @RequestBody(description = "Request body for creating consent", required = true,
                    content = @Content(schema = @Schema(implementation = CreateConsentRequest.class))),
            parameters = {
                    @Parameter(name = "txn", description = "Transaction ID (UUID)", required = true, example = "a1b2c3d4-e5f6-7890-1234-567890abcdef"),
                    @Parameter(name = "tenant-id", description = "Tenant ID (UUID)", required = true, example = "a1b2c3d4-e5f6-7890-1234-567890abcdef")
            },
            responses = {
                    @ApiResponse(responseCode = "201", description = "Consent created successfully",
                            content = @Content(schema = @Schema(implementation = ConsentCreateResponse.class))),
                    @ApiResponse(responseCode = "500", description = "Internal server error")
            }
    )
    public ResponseEntity<ConsentCreateResponse> createConsentByConsentHandleId(@org.springframework.web.bind.annotation.RequestBody CreateConsentRequest request, @RequestHeader Map<String, String> headers) throws Exception {
        return new ResponseEntity<>(this.consentService.createConsentByConsentHandleId(request), HttpStatus.CREATED);
    }
}
