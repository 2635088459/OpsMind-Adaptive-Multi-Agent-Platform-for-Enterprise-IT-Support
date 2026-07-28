package dev.opsmind.ticketworkflow.ticket.persistence;

import dev.opsmind.ticketworkflow.support.AbstractAddTicketMessageIT;
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

import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SPEC-TW-004 §12: any failure inside the single Add Ticket Message
 * transaction rolls back everything — including the idempotency
 * reservation itself (BI-095) — leaving no Message, Audit, Outbox, or
 * completed Idempotency record behind.
 */
@Tag("integration")
@Import(AddTicketMessageAtomicityIT.FailureInjectionConfiguration.class)
class AddTicketMessageAtomicityIT extends AbstractAddTicketMessageIT {

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
    void shouldRollBackEverythingWhenAuditInsertFails() {
        UUID ticketId = seedTicket(DEFAULT_REQUESTER, DEFAULT_APPLICATION_CODE, "INVESTIGATING");
        auditRecordPort.failOnNextAppend.set(true);

        ResponseEntity<String> response = addMessage(
            ticketId, employeeToken(DEFAULT_REQUESTER), UUID.randomUUID().toString(), employeeBody(DEFAULT_CONTENT)
        );

        assertThat(response.getStatusCode().is5xxServerError()).isTrue();
        assertNothingCommitted(ticketId);
    }

    @Test
    void shouldRollBackEverythingWhenOutboxInsertFails() {
        UUID ticketId = seedTicket(DEFAULT_REQUESTER, DEFAULT_APPLICATION_CODE, "INVESTIGATING");
        outboxEventRepository.failOnNextAppend.set(true);

        ResponseEntity<String> response = addMessage(
            ticketId, employeeToken(DEFAULT_REQUESTER), UUID.randomUUID().toString(), employeeBody(DEFAULT_CONTENT)
        );

        assertThat(response.getStatusCode().is5xxServerError()).isTrue();
        assertNothingCommitted(ticketId);
    }

    private void assertNothingCommitted(UUID ticketId) {
        assertThat(countRows("ticket.ticket_messages")).isZero();
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
