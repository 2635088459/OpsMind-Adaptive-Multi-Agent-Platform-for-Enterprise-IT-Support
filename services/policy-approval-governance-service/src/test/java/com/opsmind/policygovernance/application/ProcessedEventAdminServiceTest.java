package com.opsmind.policygovernance.application;

import com.opsmind.policygovernance.application.exception.ProcessedEventNotFoundException;
import com.opsmind.policygovernance.application.model.ProcessedEventRecord;
import com.opsmind.policygovernance.domain.audit.GovernanceAuditRecord;
import com.opsmind.policygovernance.infrastructure.audit.SimpleAuditIntegrityAdapter;
import com.opsmind.policygovernance.support.FakeMessageBrokerPublisher;
import com.opsmind.policygovernance.support.InMemoryGovernanceAuditRepository;
import com.opsmind.policygovernance.support.InMemoryOutboxEventRepository;
import com.opsmind.policygovernance.support.InMemoryProcessedEventRepository;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * SPEC-PG-034 (goal: "admin-safe repair flow for governance event
 * replay/backfill"). {@link ConsumedEventDeduplicationService} already had
 * unit coverage (SPEC-PG-025) for the write half; this class covers the
 * new review/repair half.
 */
@Tag("unit")
class ProcessedEventAdminServiceTest {

    private final Clock clock = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);
    private final InMemoryProcessedEventRepository processedEventRepository = new InMemoryProcessedEventRepository();
    private final InMemoryGovernanceAuditRepository auditRepository = new InMemoryGovernanceAuditRepository();
    private final GovernanceAuditService auditService = new GovernanceAuditService(
        auditRepository, new SimpleAuditIntegrityAdapter(),
        new OutboxDispatchService(new InMemoryOutboxEventRepository(), new FakeMessageBrokerPublisher(), clock), clock
    );
    private final ProcessedEventAdminService service = new ProcessedEventAdminService(processedEventRepository, auditService);

    @Test
    void findByEventIdReturnsEveryConsumerThatProcessedIt() {
        processedEventRepository.markProcessedIfNew("evt-1", "policy-evaluation-requested-consumer", "policy.evaluation.requested.v1");
        processedEventRepository.markProcessedIfNew("evt-1", "tool-approval-required-consumer", "policy.evaluation.requested.v1");
        processedEventRepository.markProcessedIfNew("evt-2", "policy-evaluation-requested-consumer", "policy.evaluation.requested.v1");

        List<ProcessedEventRecord> found = service.findByEventId("evt-1");

        assertThat(found).extracting(ProcessedEventRecord::consumerName)
            .containsExactlyInAnyOrder("policy-evaluation-requested-consumer", "tool-approval-required-consumer");
    }

    @Test
    void findByEventIdReturnsEmptyWhenNoConsumerHasProcessedIt() {
        assertThat(service.findByEventId("evt-never-seen")).isEmpty();
    }

    /**
     * The core repair behavior: after {@link #backfill}, {@link
     * com.opsmind.policygovernance.application.port.ProcessedEventRepository#markProcessedIfNew}
     * accepts the same {@code (eventId, consumerName)} pair as new again —
     * proving a redelivery would no longer be silently absorbed.
     */
    @Test
    void backfillClearsTheDedupMarkerSoTheEventCanBeReprocessed() {
        processedEventRepository.markProcessedIfNew("evt-1", "policy-evaluation-requested-consumer", "policy.evaluation.requested.v1");

        service.backfill("evt-1", "policy-evaluation-requested-consumer", "admin-1", "reprocessing after bugfix", "corr-1");

        assertThat(processedEventRepository.findByEventId("evt-1")).isEmpty();
        assertThat(processedEventRepository.markProcessedIfNew("evt-1", "policy-evaluation-requested-consumer", "policy.evaluation.requested.v1"))
            .as("the marker was cleared, so this must be accepted as new again")
            .isTrue();
    }

    /** "Admin-safe repair": the backfill itself is a governance fact worth tracing (INV-PG-008), not something to leave silent. */
    @Test
    void backfillWritesItsOwnAuditRecord() {
        processedEventRepository.markProcessedIfNew("evt-1", "policy-evaluation-requested-consumer", "policy.evaluation.requested.v1");

        service.backfill("evt-1", "policy-evaluation-requested-consumer", "admin-1", "reprocessing after bugfix", "corr-1");

        assertThat(auditService.findByCorrelationId("corr-1"))
            .extracting(GovernanceAuditRecord::action)
            .containsExactly(GovernanceAuditRecord.Action.PROCESSED_EVENT_BACKFILLED);
    }

    /** Rejects a pair that was never marked processed rather than silently no-op'ing — nothing to repair, and no audit record should be written either. */
    @Test
    void backfillRejectsAPairThatWasNeverProcessed() {
        assertThatThrownBy(() -> service.backfill("evt-never-seen", "some-consumer", "admin-1", "reason", "corr-1"))
            .isInstanceOf(ProcessedEventNotFoundException.class);

        assertThat(auditService.findByCorrelationId("corr-1")).isEmpty();
    }
}
