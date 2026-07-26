package dev.opsmind.ticketworkflow.ticket.application;

import dev.opsmind.ticketworkflow.support.GetTicketFixtures;
import dev.opsmind.ticketworkflow.ticket.application.exception.TicketNotFoundException;
import dev.opsmind.ticketworkflow.ticket.application.observability.TicketTelemetry;
import dev.opsmind.ticketworkflow.ticket.application.policy.TicketResourceAccessPolicy;
import dev.opsmind.ticketworkflow.ticket.application.policy.TicketViewPolicy;
import dev.opsmind.ticketworkflow.ticket.application.port.out.ClockPort;
import dev.opsmind.ticketworkflow.ticket.application.port.out.SensitiveReadAuditPort;
import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketQueryPort;
import dev.opsmind.ticketworkflow.ticket.application.query.ConditionalGetResult;
import dev.opsmind.ticketworkflow.ticket.application.query.EmployeeTicketProjection;
import dev.opsmind.ticketworkflow.ticket.application.query.GetTicketQuery;
import dev.opsmind.ticketworkflow.ticket.application.query.TicketViewType;
import dev.opsmind.ticketworkflow.ticket.application.service.GetTicketApplicationService;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * SPEC-TW-002 §15: authentication, scope authorization, and resource
 * authorization must all run before a {@code 304} is produced, and a
 * conditional response never leaks resource existence or version to an
 * unauthorized caller.
 */
@Tag("unit")
class GetTicketConditionalRequestTest {

    private static final UUID TICKET_ID = GetTicketFixtures.DEFAULT_TICKET_ID;

    private TicketQueryPort ticketQueryPort;
    private GetTicketApplicationService service;

    @BeforeEach
    void setUp() {
        ticketQueryPort = mock(TicketQueryPort.class);
        SensitiveReadAuditPort sensitiveReadAuditPort = mock(SensitiveReadAuditPort.class);
        TicketTelemetry telemetry = mock(TicketTelemetry.class);
        ClockPort clock = mock(ClockPort.class);
        when(clock.now()).thenReturn(Instant.parse("2026-07-23T16:30:00Z"));

        service = new GetTicketApplicationService(
            ticketQueryPort, sensitiveReadAuditPort, new TicketViewPolicy(), new TicketResourceAccessPolicy(), telemetry, clock
        );
    }

    @Test
    void shouldReturnNotModifiedWhenIfNoneMatchMatchesCurrentVersion() {
        EmployeeTicketProjection projection = GetTicketFixtures.employeeProjection(TICKET_ID);
        when(ticketQueryPort.findEmployeeView(any(), anyString())).thenReturn(Optional.of(projection));

        GetTicketQuery query = new GetTicketQuery(
            TicketId.of(TICKET_ID), GetTicketFixtures.employeeActor("employee-123"), Set.of(), "\"0\""
        );

        ConditionalGetResult result = service.get(query);

        assertThat(result).isEqualTo(new ConditionalGetResult.NotModified(0L));
    }

    @Test
    void shouldReturnFoundWhenIfNoneMatchDoesNotMatch() {
        EmployeeTicketProjection projection = GetTicketFixtures.employeeProjection(TICKET_ID);
        when(ticketQueryPort.findEmployeeView(any(), anyString())).thenReturn(Optional.of(projection));

        GetTicketQuery query = new GetTicketQuery(
            TicketId.of(TICKET_ID), GetTicketFixtures.employeeActor("employee-123"), Set.of(), "\"7\""
        );

        ConditionalGetResult result = service.get(query);

        assertThat(result).isInstanceOf(ConditionalGetResult.Found.class);
    }

    @Test
    void shouldRunAuthorizationBeforeEvaluatingConditionalRequest() {
        when(ticketQueryPort.findEmployeeView(any(), anyString())).thenReturn(Optional.empty());

        GetTicketQuery query = new GetTicketQuery(
            TicketId.of(TICKET_ID), GetTicketFixtures.employeeActor("employee-999"), Set.of(), "\"0\""
        );

        assertThatThrownBy(() -> service.get(query)).isInstanceOf(TicketNotFoundException.class);
        verify(ticketQueryPort, never()).findSupportView(any(), any());
    }
}
