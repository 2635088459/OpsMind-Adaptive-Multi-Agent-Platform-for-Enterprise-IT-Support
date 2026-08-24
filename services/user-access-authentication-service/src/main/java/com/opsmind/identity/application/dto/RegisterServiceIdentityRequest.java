package com.opsmind.identity.application.dto;

import jakarta.validation.constraints.NotBlank;

import java.time.Instant;
import java.util.List;

public record RegisterServiceIdentityRequest(
    @NotBlank String tenantId,
    @NotBlank String clientId,
    @NotBlank String serviceName,
    List<String> allowedAudiences,
    List<String> allowedScopes,
    Instant validFrom,
    Instant validUntil
) {
}
