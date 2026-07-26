package dev.opsmind.ticketworkflow.ticket.persistence;

import dev.opsmind.ticketworkflow.support.AbstractGetTicketIT;
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

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SPEC-TW-002 §16: when the required sensitive-read audit insert fails, the
 * Get Ticket read fails closed — 500 INTERNAL_ERROR, no sensitive Ticket
 * body returned.
 */
@Tag("integration")
@Import(GetTicketAuditFailureIT.FailureInjectionConfiguration.class)
class GetTicketAuditFailureIT extends AbstractGetTicketIT {

    @Autowired
    private FailureInjectingAuditRecordPort auditRecordPort;

    @BeforeEach
    void resetFailureFlag() {
        auditRecordPort.failOnNextAppend.set(false);
    }

    @Test
    void shouldFailClosedWhenSensitiveReadAuditInsertFails() {
        UUID ticketId = seedTicket(DEFAULT_REQUESTER, "HOUSING_PORTAL");
        auditRecordPort.failOnNextAppend.set(true);

        ResponseEntity<String> response = getTicket(ticketId, supportToken("support-100", List.of("HOUSING_PORTAL")));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody()).contains("INTERNAL_ERROR");
        assertThat(response.getBody()).doesNotContain("Cannot sign in to Housing Portal");
        assertThat(response.getBody()).doesNotContain("Duo keeps asking me to enroll again.");
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
