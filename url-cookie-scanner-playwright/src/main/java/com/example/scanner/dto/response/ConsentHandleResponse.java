package com.example.scanner.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
@AllArgsConstructor
@NoArgsConstructor
@Schema(description = "Response after successfully creating a consent handle")
public class ConsentHandleResponse {
    @Schema(
            description = "Unique consent handle ID - use this in consent creation",
            example = "handle_123e4567-e89b-12d3-a456-426614174000"
    )
    private String consentHandleId;
    @Schema(
            description = "Success message",
            example = "Consent Handle Created successfully!"
    )
    private String message;
    @Schema(
            description = "Transaction ID from request header",
            example = "a1b2c3d4-e5f6-7890-1234-567890abcdef"
    )
    private String txnId;

    @Schema(description = "Indicates if this is a newly created handle or existing one")
    private boolean isNewHandle;
}