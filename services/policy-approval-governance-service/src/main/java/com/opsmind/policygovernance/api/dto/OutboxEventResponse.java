package com.opsmind.policygovernance.api.dto;

import com.opsmind.policygovernance.application.model.OutboxEventRecord;
import com.opsmind.policygovernance.application.model.OutboxEventStatus;

import java.time.Instant;

/**
 * SPEC-PG-024: response shape for {@code POST
 * /api/v1/admin/outbox/{outboxId}:requeue}. Deliberately omits {@code
 * payloadJson} — the same "metadata by default, not raw content" posture
 * api-contract already applies to the audit API; the payload itself is
 * republished to the broker on the next dispatch, not something this
 * response needs to echo back.
 */
public record OutboxEventResponse(
    String outboxId,
    String aggregateType,
    String aggregateId,
    String eventType,
    OutboxEventStatus status,
    int attemptCount,
    Instant availableAt,
    Instant publishedAt,
    Instant occurredAt
) {

    public static OutboxEventResponse from(OutboxEventRecord record) {
        return new OutboxEventResponse(
            record.outboxId(), record.aggregateType(), record.aggregateId(), record.eventType(),
            record.status(), record.attemptCount(), record.availableAt(), record.publishedAt(), record.occurredAt()
        );
    }
}
