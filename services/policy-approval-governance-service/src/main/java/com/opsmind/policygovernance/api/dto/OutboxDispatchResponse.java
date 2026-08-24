package com.opsmind.policygovernance.api.dto;

import com.opsmind.policygovernance.application.OutboxDispatchService;

/** SPEC-PG-024: response shape for {@code POST /api/v1/admin/outbox:dispatch}. */
public record OutboxDispatchResponse(int published, int retried, int deadLettered) {

    public static OutboxDispatchResponse from(OutboxDispatchService.DrainResult result) {
        return new OutboxDispatchResponse(result.published(), result.retried(), result.deadLettered());
    }
}
