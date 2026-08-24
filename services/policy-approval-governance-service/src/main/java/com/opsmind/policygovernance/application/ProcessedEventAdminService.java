package com.opsmind.policygovernance.application;

import com.opsmind.policygovernance.application.exception.ProcessedEventNotFoundException;
import com.opsmind.policygovernance.application.model.ProcessedEventRecord;
import com.opsmind.policygovernance.application.port.ProcessedEventRepository;
import com.opsmind.policygovernance.domain.audit.GovernanceAuditRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * SPEC-PG-034 (goal: "admin-safe repair flow for governance event
 * replay/backfill"). Idempotency protection itself (06-event-contracts
 * §Idempotency: "Every consumer deduplicates by eventId + consumerName")
 * has existed since SPEC-PG-025 ({@link ConsumedEventDeduplicationService}) —
 * this class is the "admin-safe repair" half that never existed: a
 * deliberate, audited way to clear one {@code processed_events} dedup
 * marker so a genuinely-needed redelivery is not silently absorbed as a
 * no-op.
 *
 * <p>Kept as its own service rather than added to {@link
 * ConsumedEventDeduplicationService}, mirroring {@code OutboxAdminService}'s
 * own precedent for exactly the same reason: that service's own callers
 * (the four inbound consumers) have no business depending on an admin
 * repair surface, and {@link GovernanceAuditService} already depends on
 * {@link OutboxDispatchService} to stage its own outbox row — a circular
 * dependency risk this separate class avoids the same way {@code
 * OutboxAdminService} does.
 */
@Service
public class ProcessedEventAdminService {

    private static final Logger log = LoggerFactory.getLogger(ProcessedEventAdminService.class);

    private final ProcessedEventRepository processedEventRepository;
    private final GovernanceAuditService auditService;

    public ProcessedEventAdminService(ProcessedEventRepository processedEventRepository, GovernanceAuditService auditService) {
        this.processedEventRepository = processedEventRepository;
        this.auditService = auditService;
    }

    /** "Review" — every consumer that has recorded {@code eventId} as processed, before deciding whether a backfill is actually needed. */
    @Transactional(readOnly = true)
    public List<ProcessedEventRecord> findByEventId(String eventId) {
        return processedEventRepository.findByEventId(eventId);
    }

    /**
     * "Backfill": clears the {@code (eventId, consumerName)} dedup marker
     * and writes a {@code PROCESSED_EVENT_BACKFILLED} audit record in the
     * same transaction (SPEC-PG-001 domain rule: "every governance state
     * transition must write audit/outbox in the same transaction") — the
     * backfill itself is the governance fact worth tracing, not something
     * to leave silent. Rejects a pair that was never marked processed
     * (nothing to repair) rather than silently no-op'ing, mirroring {@code
     * OutboxAdminService#requeue}'s own "reject a row that doesn't apply"
     * precedent.
     */
    @Transactional
    public void backfill(String eventId, String consumerName, String actorId, String reason, String correlationId) {
        if (!processedEventRepository.deleteIfExists(eventId, consumerName)) {
            throw new ProcessedEventNotFoundException(eventId, consumerName);
        }
        auditService.record(
            GovernanceAuditRecord.Action.PROCESSED_EVENT_BACKFILLED, actorId, "06", eventId,
            null, null, reason, correlationId, null, null, null, null
        );
        log.atInfo()
            .addKeyValue("correlationId", correlationId)
            .addKeyValue("eventId", eventId)
            .addKeyValue("consumerName", consumerName)
            .log("processed event backfilled");
    }
}
