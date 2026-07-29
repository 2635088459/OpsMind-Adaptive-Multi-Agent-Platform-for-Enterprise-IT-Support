package dev.opsmind.ticketworkflow.ticket.persistence;

import dev.opsmind.ticketworkflow.support.AbstractTicketTimelineIT;
import dev.opsmind.ticketworkflow.ticket.application.model.AuditRecordEntry;
import dev.opsmind.ticketworkflow.ticket.application.port.out.AuditRecordPort;
import dev.opsmind.ticketworkflow.ticket.infrastructure.persistence.adapter.AuditPersistenceAdapter;
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

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SPEC-TW-006 §23: when the required sensitive-read Audit insert fails, the
 * Support-internal Timeline read fails closed — 500 INTERNAL_ERROR, no
 * sensitive Timeline body (item content, internal notes) leaked. Mirrors
 * {@code GetTicketAuditFailureIT}'s failure-injection pattern.
 */
@Tag("integration")
@Import(TicketTimelineAuditFailureIT.FailureInjectionConfiguration.class)
class TicketTimelineAuditFailureIT extends AbstractTicketTimelineIT {

    @Autowired
    private FailureInjectingAuditRecordPort auditRecordPort;

    @BeforeEach
    void resetFailureFlag() {
        auditRecordPort.failOnNextAppend.set(false);
    }

    @Test
    void shouldFailClosedWhenSensitiveReadAuditInsertFails() {
        UUID ticketId = seedTicket(DEFAULT_REQUESTER, DEFAULT_APPLICATION_CODE, DEFAULT_CREATED_AT);
        seedInternalSupportNote(ticketId, "very secret escalation detail", DEFAULT_CREATED_AT.plusSeconds(60));
        auditRecordPort.failOnNextAppend.set(true);

        ResponseEntity<String> response = getTimeline(ticketId, supportToken("support-100", Set.of(DEFAULT_APPLICATION_CODE), true));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody()).contains("INTERNAL_ERROR");
        assertThat(response.getBody()).doesNotContain("very secret escalation detail");
        assertThat(response.getBody()).doesNotContain("items");
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
        FailureInjectingAuditRecordPort failureInjectingAuditRecordPort(AuditPersistenceAdapter delegate) {
            return new FailureInjectingAuditRecordPort(delegate);
        }
    }
}
