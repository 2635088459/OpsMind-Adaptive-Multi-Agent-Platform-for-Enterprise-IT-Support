package com.opsmind.identity.api.error;

import java.util.Map;

/** 05-api-contracts: "Error envelope fields are code, message, correlationId, and retryable; token-validation internals are never returned." */
public record ErrorResponse(ErrorDetail error) {

    public record ErrorDetail(
        String code,
        String message,
        String correlationId,
        boolean retryable,
        Map<String, Object> details
    ) {
    }

    public static ErrorResponse of(String code, String message, String correlationId, boolean retryable) {
        return new ErrorResponse(new ErrorDetail(code, message, correlationId, retryable, Map.of()));
    }
}
