package com.opsmind.policygovernance.application;

import com.opsmind.policygovernance.application.exception.OutboxEventNotFailedException;
import com.opsmind.policygovernance.application.exception.OutboxEventNotFoundException;
import com.opsmind.policygovernance.application.model.OutboxEventRecord;
import com.opsmind.policygovernance.application.model.OutboxEventStatus;
import com.opsmind.policygovernance.domain.audit.GovernanceAuditRecord;
import com.opsmind.policygovernance.domain.shared.SimpleGovernanceEvent;
import com.opsmind.policygovernance.infrastructure.audit.SimpleAuditIntegrityAdapter;
import com.opsmind.policygovernance.support.FakeMessageBrokerPublisher;
import com.opsmind.policygovernance.support.InMemoryGovernanceAuditRepository;
import com.opsmind.policygovernance.support.InMemoryOutboxEventRepository;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * SPEC-PG-024 (10-failure-handling §Recovery). {@link OutboxAdminService}
 * sits above both {@link OutboxDispatchService} and {@link
 * GovernanceAuditService} — see that type's own javadoc for why (avoiding a
 * circular bean dependency).
 */
@Tag("unit")
class OutboxAdminServiceTest {

    private final Clock clock = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);
    private final InMemoryOutboxEventRepository outboxEventRepository = new InMemoryOutboxEventRepository();
    private final InMemoryGovernanceAuditRepository auditRepository = new InMemoryGovernanceAuditRepository();
    private final OutboxDispatchService outboxDispatchService = new OutboxDispatchService(outboxEventRepository, new FakeMessageBrokerPublisher(), clock);
    private final GovernanceAuditService auditService = new GovernanceAuditService(
        auditRepository, new SimpleAuditIntegrityAdapter(), outboxDispatchService, clock
    );
    private final OutboxAdminService service = new OutboxAdminService(outboxEventRepository, outboxDispatchService, auditService, clock);

    private String stageFailedRow() {
        String outboxId = UUID.randomUUID().toString();
        outboxEventRepository.append(new OutboxEventRecord(
            outboxId, "ApprovalRequest", "ar-1", "approval.granted.v1", "v1", "{}", "corr-1", null,
            OutboxEventStatus.FAILED, 4, null, null, clock.instant()
        ));
        return outboxId;
    }

    /** "Outbox replay": OutboxAdminService#dispatchPending is a thin pass-through to the already-tested OutboxDispatchService#publishPending. */
    @Test
    void dispatchPendingDrainsDuePendingRows() {
        outboxDispatchService.stage(SimpleGovernanceEvent.of("approval.granted.v1", "corr-1", null));

        OutboxDispatchService.DrainResult result = service.dispatchPending();

        assertThat(result.published()).isEqualTo(1);
    }

    /** "Poison repair": a dead-lettered row is reset to PENDING with a fresh attempt budget. */
    @Test
    void requeueResetsADeadLetteredRowToPendingWithAFreshAttemptBudget() {
        String outboxId = stageFailedRow();

        OutboxEventRecord requeued = service.requeue(outboxId, "admin-1", "root cause fixed", "corr-1");

        assertThat(requeued.status()).isEqualTo(OutboxEventStatus.PENDING);
        assertThat(requeued.attemptCount()).isZero();
    }

    /** Requeue writes an OUTBOX_EVENT_REQUEUED audit fact — the "admin change" this record type exists to trace. */
    @Test
    void requeueWritesAnAuditRecord() {
        String outboxId = stageFailedRow();

        service.requeue(outboxId, "admin-1", "root cause fixed", "corr-requeue-1");

        assertThat(auditRepository.findByCorrelationId("corr-requeue-1"))
            .anySatisfy(record -> {
                assertThat(record.action()).isEqualTo(GovernanceAuditRecord.Action.OUTBOX_EVENT_REQUEUED);
                assertThat(record.actorId()).isEqualTo("admin-1");
                assertThat(record.sourceRequestId()).isEqualTo(outboxId);
            });
    }

    @Test
    void requeueThrowsWhenTheOutboxEventDoesNotExist() {
        assertThatThrownBy(() -> service.requeue("does-not-exist", "admin-1", "reason", "corr-1"))
            .isInstanceOf(OutboxEventNotFoundException.class);
    }

    /** 08-transaction-and-outbox: only a dead-lettered (FAILED) row can be repaired — a still-PENDING row needs no repair. */
    @Test
    void requeueThrowsWhenTheOutboxEventIsNotFailed() {
        String outboxId = UUID.randomUUID().toString();
        outboxEventRepository.append(new OutboxEventRecord(
            outboxId, "ApprovalRequest", "ar-1", "approval.granted.v1", "v1", "{}", "corr-1", null,
            OutboxEventStatus.PENDING, 0, null, null, clock.instant()
        ));

        assertThatThrownBy(() -> service.requeue(outboxId, "admin-1", "reason", "corr-1"))
            .isInstanceOf(OutboxEventNotFailedException.class);
    }

    /** A published row is also not eligible — repairing it would risk a duplicate publish of an event that already delivered. */
    @Test
    void requeueThrowsWhenTheOutboxEventIsAlreadyPublished() {
        String outboxId = UUID.randomUUID().toString();
        outboxEventRepository.append(new OutboxEventRecord(
            outboxId, "ApprovalRequest", "ar-1", "approval.granted.v1", "v1", "{}", "corr-1", null,
            OutboxEventStatus.PUBLISHED, 0, null, clock.instant(), clock.instant()
        ));

        assertThatThrownBy(() -> service.requeue(outboxId, "admin-1", "reason", "corr-1"))
            .isInstanceOf(OutboxEventNotFailedException.class);
    }
}
