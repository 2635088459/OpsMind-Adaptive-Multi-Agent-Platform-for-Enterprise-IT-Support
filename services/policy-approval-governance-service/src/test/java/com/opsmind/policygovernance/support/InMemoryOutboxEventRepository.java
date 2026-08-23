package com.opsmind.policygovernance.support;

import com.opsmind.policygovernance.application.model.OutboxEventRecord;
import com.opsmind.policygovernance.application.model.OutboxEventStatus;
import com.opsmind.policygovernance.application.port.OutboxEventRepository;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Fast, in-process test double for {@link OutboxEventRepository} — see {@link InMemoryPolicyRepository}. */
public class InMemoryOutboxEventRepository implements OutboxEventRepository {

    private final Map<String, OutboxEventRecord> byId = new LinkedHashMap<>();

    @Override
    public void append(OutboxEventRecord record) {
        byId.put(record.outboxId(), record);
    }

    @Override
    public List<OutboxEventRecord> findPendingBatch(Instant now, int limit) {
        return byId.values().stream()
            .filter(r -> r.status() == OutboxEventStatus.PENDING)
            .filter(r -> r.availableAt() == null || !r.availableAt().isAfter(now))
            .sorted((a, b) -> a.occurredAt().compareTo(b.occurredAt()))
            .limit(limit)
            .toList();
    }

    @Override
    public void markPublished(String outboxId, Instant publishedAt) {
        replace(outboxId, r -> new OutboxEventRecord(
            r.outboxId(), r.aggregateType(), r.aggregateId(), r.eventType(), r.eventVersion(), r.payloadJson(),
            r.correlationId(), r.causationId(), OutboxEventStatus.PUBLISHED, r.attemptCount(), r.availableAt(), publishedAt, r.occurredAt()
        ));
    }

    @Override
    public void markRetry(String outboxId, int attemptCount, Instant nextAvailableAt) {
        replace(outboxId, r -> new OutboxEventRecord(
            r.outboxId(), r.aggregateType(), r.aggregateId(), r.eventType(), r.eventVersion(), r.payloadJson(),
            r.correlationId(), r.causationId(), OutboxEventStatus.PENDING, attemptCount, nextAvailableAt, r.publishedAt(), r.occurredAt()
        ));
    }

    @Override
    public void markFailed(String outboxId) {
        replace(outboxId, r -> new OutboxEventRecord(
            r.outboxId(), r.aggregateType(), r.aggregateId(), r.eventType(), r.eventVersion(), r.payloadJson(),
            r.correlationId(), r.causationId(), OutboxEventStatus.FAILED, r.attemptCount(), r.availableAt(), r.publishedAt(), r.occurredAt()
        ));
    }

    @Override
    public long countPending() {
        return byId.values().stream().filter(r -> r.status() == OutboxEventStatus.PENDING).count();
    }

    public List<OutboxEventRecord> all() {
        return List.copyOf(byId.values());
    }

    private void replace(String outboxId, java.util.function.UnaryOperator<OutboxEventRecord> updater) {
        OutboxEventRecord current = byId.get(outboxId);
        if (current != null) {
            byId.put(outboxId, updater.apply(current));
        }
    }
}
