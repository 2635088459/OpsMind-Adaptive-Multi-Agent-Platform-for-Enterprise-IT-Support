package dev.opsmind.ticketworkflow.ticket.application;

import dev.opsmind.ticketworkflow.support.GetTicketFixtures;
import dev.opsmind.ticketworkflow.ticket.application.exception.TicketAuthorizationException;
import dev.opsmind.ticketworkflow.ticket.application.observability.TicketTelemetry;
import dev.opsmind.ticketworkflow.ticket.application.policy.SensitiveReadAuditDecisionRecorder;
import dev.opsmind.ticketworkflow.ticket.application.policy.TicketResourceAccessPolicy;
import dev.opsmind.ticketworkflow.ticket.application.policy.TicketViewPolicy;
import dev.opsmind.ticketworkflow.ticket.application.port.out.ClockPort;
import dev.opsmind.ticketworkflow.ticket.application.port.out.SensitiveReadAuditPort;
import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketQueryPort;
import dev.opsmind.ticketworkflow.ticket.application.query.GetTicketQuery;
import dev.opsmind.ticketworkflow.ticket.application.query.TicketViewType;
import dev.opsmind.ticketworkflow.ticket.application.service.GetTicketApplicationService;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * SPEC-TW-002 §14: this Spec defines only the Auditor policy hook. The
 * Auditor role resolves to a distinct view (proving the hook exists) but
 * this endpoint does not yet implement a concrete Auditor projection, so
 * every Auditor read is denied rather than silently downgraded to another
 * view or allowed through with unapproved fields.
 */
@Tag("security")
class GetTicketAuditorPolicyTest {

    @Test
    void shouldResolveAuditorToItsOwnViewType() {
        TicketViewPolicy policy = new TicketViewPolicy();

        assertThat(policy.resolve(GetTicketFixtures.auditorActor("auditor-1"))).isEqualTo(TicketViewType.AUDITOR_VIEW);
    }

    @Test
    void shouldDenyAuditorReadWithoutQueryingOrLeakingAnyField() {
        TicketQueryPort ticketQueryPort = mock(TicketQueryPort.class);
        GetTicketApplicationService service = new GetTicketApplicationService(
            ticketQueryPort,
            mock(SensitiveReadAuditPort.class),
            new TicketViewPolicy(),
            new TicketResourceAccessPolicy(),
            mock(TicketTelemetry.class),
            mock(ClockPort.class)
        , mock(SensitiveReadAuditDecisionRecorder.class));

        GetTicketQuery query = GetTicketFixtures.employeeQuery(
            GetTicketFixtures.DEFAULT_TICKET_ID, GetTicketFixtures.auditorActor("auditor-1")
        );

        assertThatThrownBy(() -> service.get(query)).isInstanceOf(TicketAuthorizationException.class);
        verify(ticketQueryPort, never()).findEmployeeView(any(), anyString());
        verify(ticketQueryPort, never()).findSupportView(any(), any());
    }
}
