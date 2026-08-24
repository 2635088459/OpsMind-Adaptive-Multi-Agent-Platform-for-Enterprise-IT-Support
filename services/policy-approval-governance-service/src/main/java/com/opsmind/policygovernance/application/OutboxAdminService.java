package com.opsmind.policygovernance.application;

import com.opsmind.policygovernance.application.exception.OutboxEventNotFailedException;
import com.opsmind.policygovernance.application.exception.OutboxEventNotFoundException;
import com.opsmind.policygovernance.application.model.OutboxEventRecord;
import com.opsmind.policygovernance.application.model.OutboxEventStatus;
import com.opsmind.policygovernance.application.port.OutboxEventRepository;
import com.opsmind.policygovernance.domain.audit.GovernanceAuditRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;

/**
 * SPEC-PG-024 (10-failure-handling §Recovery: "1. replay pending outbox ...
 * 4. reschedule poison review"): the admin entry points for {@link
 * OutboxDispatchService}'s own two documented but previously unreachable
 * seams — "replay" (draining pending rows on demand, already fully built by
 * {@link OutboxDispatchService#publishPending()} since SPEC-PG-003, just
 * never given an HTTP entry point) and "poison repair" (giving a
 * dead-lettered/{@code FAILED} row — 08-transaction-and-outbox §Outbox:
 * "Publisher must use ... dead-letter state" — a fresh retry budget).
 *
 * <p>Kept as its own service rather than added to {@link
 * OutboxDispatchService} itself: {@link GovernanceAuditService} already
 * depends on {@link OutboxDispatchService} (to stage the audit's own
 * outbox row) — {@link OutboxDispatchService} depending back on {@link
 * GovernanceAuditService} to audit a requeue would be a circular bean
 * dependency. This type sits above both instead, the only shape that lets
 * requeue be audited (INV-PG-008; 01-domain-model §GovernanceAudit names
 * "admin change" as one of the fact categories this record type covers)
 * without introducing one.
 */
@Service
public class OutboxAdminService {

    private static final Logger log = LoggerFactory.getLogger(OutboxAdminService.class);

    private final OutboxEventRepository outboxEventRepository;
    private final OutboxDispatchService outboxDispatchService;
    private final GovernanceAuditService auditService;
    private final Clock clock;

    public OutboxAdminService(
        OutboxEventRepository outboxEventRepository, OutboxDispatchService outboxDispatchService,
        GovernanceAuditService auditService, Clock clock
    ) {
        this.outboxEventRepository = outboxEventRepository;
        this.outboxDispatchService = outboxDispatchService;
        this.auditService = auditService;
        this.clock = clock;
    }

    /** "Outbox replay": drains due {@code PENDING} rows exactly as {@link OutboxDispatchService#publishPending()} always has. */
    public OutboxDispatchService.DrainResult dispatchPending() {
        return outboxDispatchService.publishPending();
    }

    /**
     * "Poison repair": resets a {@code FAILED} row back to {@code PENDING}
     * with a fresh attempt budget (see {@link OutboxEventRepository#requeue}
     * for why the count resets rather than continues) and writes an {@code
     * OUTBOX_EVENT_REQUEUED} audit record — the requeue itself is the
     * "admin change" this fact type exists to trace, not something to leave
     * silent.
     */
    @Transactional
    public OutboxEventRecord requeue(String outboxId, String actorId, String reason, String correlationId) {
        OutboxEventRecord record = outboxEventRepository.findById(outboxId)
            .orElseThrow(() -> new OutboxEventNotFoundException(outboxId));
        if (record.status() != OutboxEventStatus.FAILED) {
            throw new OutboxEventNotFailedException(outboxId, record.status());
        }

        Instant now = clock.instant();
        outboxEventRepository.requeue(outboxId, now);
        auditService.record(
            GovernanceAuditRecord.Action.OUTBOX_EVENT_REQUEUED, actorId, "06", outboxId,
            null, null, reason, correlationId, null, null, null, null
        );
        log.atInfo()
            .addKeyValue("correlationId", correlationId)
            .addKeyValue("outboxId", outboxId)
            .addKeyValue("eventType", record.eventType())
            .log("outbox event requeued");

        return outboxEventRepository.findById(outboxId).orElseThrow(() -> new OutboxEventNotFoundException(outboxId));
    }
}
