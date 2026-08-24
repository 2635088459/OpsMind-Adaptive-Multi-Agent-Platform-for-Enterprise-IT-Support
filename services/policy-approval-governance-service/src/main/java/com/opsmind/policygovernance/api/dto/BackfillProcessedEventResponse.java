package com.opsmind.policygovernance.api.dto;

/** SPEC-PG-034: response shape for {@code POST /api/v1/admin/processed-events/{eventId}/{consumerName}:backfill}. */
public record BackfillProcessedEventResponse(String eventId, String consumerName, boolean backfilled) {
}
