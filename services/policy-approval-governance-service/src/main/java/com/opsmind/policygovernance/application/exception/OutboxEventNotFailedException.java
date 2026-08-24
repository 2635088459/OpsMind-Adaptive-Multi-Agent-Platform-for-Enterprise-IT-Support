package com.opsmind.policygovernance.application.exception;

import com.opsmind.policygovernance.application.model.OutboxEventStatus;

/**
 * Thrown when {@code OutboxAdminService#requeue} targets an outbox row that
 * is not currently {@code FAILED} (dead-lettered) — 08-transaction-and-outbox
 * §Outbox: "Publisher must use stable eventId, publish confirm, retry, and
 * dead-letter state." Requeue exists to repair a poisoned (dead-lettered)
 * row specifically; a {@code PENDING} row needs no repair and a {@code
 * PUBLISHED} row has already succeeded — resetting either back to {@code
 * PENDING} would risk a duplicate publish of an event that either never
 * failed or already delivered.
 */
public class OutboxEventNotFailedException extends RuntimeException {

    public OutboxEventNotFailedException(String outboxId, OutboxEventStatus actualStatus) {
        super("outbox event " + outboxId + " is not FAILED (actual status: " + actualStatus + "); only a dead-lettered event can be requeued");
    }
}
