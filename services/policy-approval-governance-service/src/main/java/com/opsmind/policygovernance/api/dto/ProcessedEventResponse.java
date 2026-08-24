package com.opsmind.policygovernance.api.dto;

import com.opsmind.policygovernance.application.model.ProcessedEventRecord;

import java.time.Instant;

/** SPEC-PG-034: response shape for {@code GET /api/v1/admin/processed-events}. */
public record ProcessedEventResponse(String eventId, String consumerName, String eventType, Instant processedAt) {

    public static ProcessedEventResponse from(ProcessedEventRecord record) {
        return new ProcessedEventResponse(record.eventId(), record.consumerName(), record.eventType(), record.processedAt());
    }
}
