package com.example.scanner.enums;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Status of consent handle")
public enum ConsentHandleStatus {
    @Schema(description = "Handle is pending activation")
    PENDING,

    @Schema(description = "Handle is active and can be used")
    ACTIVE,

    @Schema(description = "Handle has expired")
    EXPIRED,

    @Schema(description = "Handle has been used and consumed")
    USED,

    @Schema(description = "Handle was rejected - all preferences were NOTACCEPTED")
    REJECTED
}