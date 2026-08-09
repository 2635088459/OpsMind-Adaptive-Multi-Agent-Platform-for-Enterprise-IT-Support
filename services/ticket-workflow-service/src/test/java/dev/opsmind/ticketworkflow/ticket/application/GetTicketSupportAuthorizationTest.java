package dev.opsmind.ticketworkflow.ticket.application;

import dev.opsmind.ticketworkflow.support.GetTicketFixtures;
import dev.opsmind.ticketworkflow.ticket.application.exception.TicketNotFoundException;
import dev.opsmind.ticketworkflow.ticket.application.observability.TicketTelemetry;
import dev.opsmind.ticketworkflow.ticket.application.policy.SensitiveReadAuditDecisionRecorder;
import dev.opsmind.ticketworkflow.ticket.application.policy.TicketResourceAccessPolicy;
import dev.opsmind.ticketworkflow.ticket.application.policy.TicketViewPolicy;
import dev.opsmind.ticketworkflow.ticket.application.port.out.ClockPort;
import dev.opsmind.ticketworkflow.ticket.application.port.out.SensitiveReadAuditPort;
import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketQueryPort;
import dev.opsmind.ticketworkflow.ticket.application.query.ConditionalGetResult;
import dev.opsmind.ticketworkflow.ticket.application.query.GetTicketQuery;
import dev.opsmind.ticketworkflow.ticket.application.query.SupportTicketProjection;
import dev.opsmind.ticketworkflow.ticket.application.service.GetTicketApplicationService;
import dev.opsmind.ticketworkflow.ticket.domain.value.ApplicationCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * SPEC-TW-002 §9: Support resource scope is enforced by allowed application
 * codes pushed into the SQL predicate, not filtered afterward in Java.
 */
@Tag("security")
class GetTicketSupportAuthorizationTest {

    private static final UUID TICKET_ID = GetTicketFixtures.DEFAULT_TICKET_ID;

    private TicketQueryPort ticketQueryPort;
    private GetTicketApplicationService service;

    @BeforeEach
    void setUp() {
        ticketQueryPort = mock(TicketQueryPort.class);
        service = new GetTicketApplicationService(
            ticketQueryPort,
            mock(SensitiveReadAuditPort.class),
            new TicketViewPolicy(),
            new TicketResourceAccessPolicy(),
            mock(TicketTelemetry.class),
            mock(ClockPort.class)
        , mock(SensitiveReadAuditDecisionRecorder.class));
    }

    @Test
    void shouldAllowSupportToReadTicketWithinAuthorizedQueue() {
        SupportTicketProjection projection = GetTicketFixtures.supportProjection(TICKET_ID, "employee-123");
        when(ticketQueryPort.findSupportView(any(), eq(Set.of(ApplicationCode.HOUSING_PORTAL))))
            .thenReturn(Optional.of(projection));

        GetTicketQuery query = GetTicketFixtures.supportQuery(
            TICKET_ID, GetTicketFixtures.supportActor("support-100"), Set.of(ApplicationCode.HOUSING_PORTAL)
        );

        assertThat(service.get(query)).isInstanceOf(ConditionalGetResult.Found.class);
    }

    @Test
    void shouldRejectSupportReadingTicketOutsideAuthorizedQueue() {
        when(ticketQueryPort.findSupportView(any(), eq(Set.of(ApplicationCode.VPN)))).thenReturn(Optional.empty());

        GetTicketQuery query = GetTicketFixtures.supportQuery(
            TICKET_ID, GetTicketFixtures.supportActor("support-100"), Set.of(ApplicationCode.VPN)
        );

        assertThatThrownBy(() -> service.get(query)).isInstanceOf(TicketNotFoundException.class);
    }
}
