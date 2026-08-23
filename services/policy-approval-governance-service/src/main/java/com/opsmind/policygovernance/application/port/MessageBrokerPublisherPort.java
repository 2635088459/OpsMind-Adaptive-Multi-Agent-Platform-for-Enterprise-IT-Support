package com.opsmind.policygovernance.application.port;

import com.opsmind.policygovernance.application.model.OutboxEventRecord;

/**
 * Port to the real message broker. Implementations must use the record's
 * own stable {@code outboxId} as the broker message id so a redelivered or
 * re-dispatched copy of the same row is still recognizable as the same
 * logical event downstream (08-transaction-and-outbox: "Publisher must use
 * stable eventId"). Throws an unchecked exception on any publish failure —
 * {@code OutboxDispatchService#publishPending} treats that as a retry/
 * dead-letter signal, never a caller-handled checked exception.
 */
public interface MessageBrokerPublisherPort {

    void publish(OutboxEventRecord record);
}
