package dev.opsmind.ticketworkflow.ticket.persistence;

import dev.opsmind.ticketworkflow.support.AbstractTicketAssignmentIT;
import dev.opsmind.ticketworkflow.ticket.application.model.AuditRecordEntry;
import dev.opsmind.ticketworkflow.ticket.application.model.OutboxEventEntry;
import dev.opsmind.ticketworkflow.ticket.application.port.out.AuditRecordPort;
import dev.opsmind.ticketworkflow.ticket.application.port.out.OutboxEventRepository;
import dev.opsmind.ticketworkflow.ticket.infrastructure.persistence.adapter.AuditPersistenceAdapter;
import dev.opsmind.ticketworkflow.ticket.infrastructure.persistence.adapter.OutboxPersistenceAdapter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.ResponseEntity;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SPEC-TW-008 AC-09: any failure inside the single Assign/Reassign/
 * Unassign transaction rolls back everything, including the ticket UPDATE
 * and the idempotency reservation itself. Mirrors {@code
 * TriageTicketRollbackIT}'s failure-injecting bean mechanism exactly.
 */
@Tag("integration")
@Import(TicketAssignmentRollbackIT.FailureInjectionConfiguration.class)
class TicketAssignmentRollbackIT extends AbstractTicketAssignmentIT {

    @Autowired
    private FailureInjectingOutboxEventRepository outboxEventRepository;

    @Autowired
    private FailureInjectingAuditRecordPort auditRecordPort;

    @BeforeEach
    void resetFailureFlags() {
        outboxEventRepository.failOnNextAppend.set(false);
        auditRecordPort.failOnNextAppend.set(false);
    }

    @Test
    void shouldRollBackEverythingWhenAuditInsertFailsOnAssign() {
        UUID queueId = seedSupportQueue(DEFAULT_TEAM_ID, true);
        UUID ticketId = seedTriagedTicketReadyToAssign(DEFAULT_TEAM_ID, queueId);
        seedSupportAgent(DEFAULT_ASSIGNEE_ID, "IT_SUPPORT", true);
        seedQueueMembership(DEFAULT_ASSIGNEE_ID, queueId);
        auditRecordPort.failOnNextAppend.set(true);

        ResponseEntity<String> response = assign(
            ticketId, supportToken("support-100", Set.of(DEFAULT_TEAM_ID)), "\"0\"", UUID.randomUUID().toString(),
            assignRequestBody(DEFAULT_ASSIGNEE_ID, DEFAULT_REASON)
        );

        assertThat(response.getStatusCode().is5xxServerError()).isTrue();
        assertNothingCommitted(ticketId, "TRIAGED", null);
    }

    @Test
    void shouldRollBackEverythingWhenOutboxInsertFailsOnAssign() {
        UUID queueId = seedSupportQueue(DEFAULT_TEAM_ID, true);
        UUID ticketId = seedTriagedTicketReadyToAssign(DEFAULT_TEAM_ID, queueId);
        seedSupportAgent(DEFAULT_ASSIGNEE_ID, "IT_SUPPORT", true);
        seedQueueMembership(DEFAULT_ASSIGNEE_ID, queueId);
        outboxEventRepository.failOnNextAppend.set(true);

        ResponseEntity<String> response = assign(
            ticketId, supportToken("support-100", Set.of(DEFAULT_TEAM_ID)), "\"0\"", UUID.randomUUID().toString(),
            assignRequestBody(DEFAULT_ASSIGNEE_ID, DEFAULT_REASON)
        );

        assertThat(response.getStatusCode().is5xxServerError()).isTrue();
        assertNothingCommitted(ticketId, "TRIAGED", null);
    }

    @Test
    void shouldRollBackEverythingWhenOutboxInsertFailsOnUnassign() {
        UUID queueId = seedSupportQueue(DEFAULT_TEAM_ID, true);
        UUID ticketId = seedAssignedTicket(DEFAULT_TEAM_ID, queueId, DEFAULT_ASSIGNEE_ID);
        outboxEventRepository.failOnNextAppend.set(true);

        ResponseEntity<String> response = unassign(
            ticketId, supportToken("support-100", Set.of(DEFAULT_TEAM_ID)), "\"0\"", UUID.randomUUID().toString(),
            unassignRequestBody(DEFAULT_REASON)
        );

        assertThat(response.getStatusCode().is5xxServerError()).isTrue();
        assertNothingCommitted(ticketId, "ASSIGNED", DEFAULT_ASSIGNEE_ID);
    }

    private void assertNothingCommitted(UUID ticketId, String expectedStatus, String expectedAssignee) {
        assertThat(ticketRow(ticketId).get("status")).isEqualTo(expectedStatus);
        assertThat(ticketRow(ticketId).get("version")).isEqualTo(0L);
        assertThat(ticketRow(ticketId).get("current_support_user_id")).isEqualTo(expectedAssignee);
        assertThat(countRows("ticket.ticket_assignment_history")).isZero();
        assertThat(countRows("ticket.ticket_status_history")).isZero();
        assertThat(countRows("ticket.audit_records")).isZero();
        assertThat(countRows("ticket.outbox_events")).isZero();
        Integer completedIdempotencyRecords = jdbcTemplate.queryForObject(
            "SELECT count(*) FROM ticket.idempotency_records WHERE status = 'COMPLETED'", Integer.class
        );
        assertThat(completedIdempotencyRecords).isZero();
    }

    static class FailureInjectingOutboxEventRepository implements OutboxEventRepository {

        private final OutboxPersistenceAdapter delegate;
        private final AtomicBoolean failOnNextAppend = new AtomicBoolean(false);

        FailureInjectingOutboxEventRepository(OutboxPersistenceAdapter delegate) {
            this.delegate = delegate;
        }

        @Override
        public void append(OutboxEventEntry entry) {
            if (failOnNextAppend.get()) {
                throw new RuntimeException("simulated outbox insert failure");
            }
            delegate.append(entry);
        }

        @Override
        public java.util.List<OutboxEventEntry> claimPublishable(
            java.time.Instant now, java.time.Instant staleLockThreshold, String workerId, int batchSize
        ) {
            return delegate.claimPublishable(now, staleLockThreshold, workerId, batchSize);
        }

        @Override
        public void markPublished(java.util.UUID outboxId, java.time.Instant publishedAt) {
            delegate.markPublished(outboxId, publishedAt);
        }

        @Override
        public void markRetry(java.util.UUID outboxId, int attempts, java.time.Instant nextAvailableAt, String errorCode) {
            delegate.markRetry(outboxId, attempts, nextAvailableAt, errorCode);
        }
    }

    static class FailureInjectingAuditRecordPort implements AuditRecordPort {

        private final AuditPersistenceAdapter delegate;
        private final AtomicBoolean failOnNextAppend = new AtomicBoolean(false);

        FailureInjectingAuditRecordPort(AuditPersistenceAdapter delegate) {
            this.delegate = delegate;
        }

        @Override
        public void append(AuditRecordEntry entry) {
            if (failOnNextAppend.get()) {
                throw new RuntimeException("simulated audit insert failure");
            }
            delegate.append(entry);
        }
    }

    @TestConfiguration
    static class FailureInjectionConfiguration {

        @Bean
        @Primary
        FailureInjectingOutboxEventRepository failureInjectingOutboxEventRepository(OutboxPersistenceAdapter delegate) {
            return new FailureInjectingOutboxEventRepository(delegate);
        }

        @Bean
        @Primary
        FailureInjectingAuditRecordPort failureInjectingAuditRecordPort(AuditPersistenceAdapter delegate) {
            return new FailureInjectingAuditRecordPort(delegate);
        }
    }
}
