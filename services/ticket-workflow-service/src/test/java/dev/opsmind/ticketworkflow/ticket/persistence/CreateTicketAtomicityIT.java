package dev.opsmind.ticketworkflow.ticket.persistence;

import dev.opsmind.ticketworkflow.support.AbstractCreateTicketIT;
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
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies BI-095 / SPEC-TW-001 §14: if any required insert fails, the whole
 * Create Ticket transaction rolls back — no Ticket, cycle, history, audit,
 * outbox, or idempotency-completion record is left behind. Two
 * representative failure points (Outbox, Audit) are injected via toggleable
 * test doubles; the rollback mechanism is the same {@code @Transactional}
 * boundary regardless of which write fails, so these two are sufficient to
 * demonstrate the property without re-testing identical plumbing seven times.
 */
@Tag("integration")
@Import(CreateTicketAtomicityIT.FailureInjectionConfiguration.class)
class CreateTicketAtomicityIT extends AbstractCreateTicketIT {

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
    void shouldRollBackEverythingWhenOutboxInsertFails() {
        outboxEventRepository.failOnNextAppend.set(true);

        ResponseEntity<String> response = createTicket("user-atomicity-outbox", newIdempotencyKey(), validRequestBody());

        assertThat(response.getStatusCode().is5xxServerError()).isTrue();
        assertNothingCommitted();
    }

    @Test
    void shouldRollBackEverythingWhenAuditInsertFails() {
        auditRecordPort.failOnNextAppend.set(true);

        ResponseEntity<String> response = createTicket("user-atomicity-audit", newIdempotencyKey(), validRequestBody());

        assertThat(response.getStatusCode().is5xxServerError()).isTrue();
        assertNothingCommitted();
    }

    private void assertNothingCommitted() {
        assertThat(countRows("ticket.tickets")).isZero();
        assertThat(countRows("ticket.ticket_resolution_cycles")).isZero();
        assertThat(countRows("ticket.ticket_sla_cycles")).isZero();
        assertThat(countRows("ticket.ticket_status_history")).isZero();
        assertThat(countRows("ticket.audit_records")).isZero();
        assertThat(countRows("ticket.outbox_events")).isZero();
        assertThat(jdbcTemplate.queryForObject(
            "SELECT count(*) FROM ticket.idempotency_records WHERE status = 'COMPLETED'", Integer.class
        )).isZero();
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
