package dev.opsmind.ticketworkflow.platform.error;

import java.util.Map;

public record ErrorResponse(ErrorDetail error) {

    public record ErrorDetail(
        String code,
        String message,
        String traceId,
        String correlationId,
        Map<String, Object> details
    ) {
    }

    public static ErrorResponse of(String code, String message, String traceId, String correlationId) {
        return new ErrorResponse(new ErrorDetail(code, message, traceId, correlationId, Map.of()));
    }
}
