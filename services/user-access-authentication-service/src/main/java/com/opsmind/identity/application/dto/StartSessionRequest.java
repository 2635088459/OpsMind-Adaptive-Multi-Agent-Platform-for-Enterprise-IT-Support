package com.opsmind.identity.application.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;

import java.util.List;

/** {@code issuer}/{@code subject} are deliberately absent — see {@code api.internal.SessionController}. */
public record StartSessionRequest(
    @NotBlank String tenantId,
    String idpSessionIdHash,
    String tokenIdHash,
    String clientId,
    @NotBlank String acr,
    List<String> amr,
    String deviceIdHash,
    @Positive long ttlSeconds
) {
}
