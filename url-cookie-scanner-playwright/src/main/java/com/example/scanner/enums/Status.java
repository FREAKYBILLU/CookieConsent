package com.example.scanner.enums;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Lifecycle status of consent")
public enum Status {
    @Schema(description = "Consent is currently active")
    ACTIVE,

    @Schema(description = "Consent has been deactivated")
    INACTIVE,

    @Schema(description = "Consent has expired")
    EXPIRED
}