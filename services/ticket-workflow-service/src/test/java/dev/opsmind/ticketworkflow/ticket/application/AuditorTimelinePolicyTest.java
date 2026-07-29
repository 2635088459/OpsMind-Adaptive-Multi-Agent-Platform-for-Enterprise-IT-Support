package dev.opsmind.ticketworkflow.ticket.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.opsmind.ticketworkflow.configuration.TicketWorkflowProperties;
import dev.opsmind.ticketworkflow.support.TicketTimelineFixtures;
import dev.opsmind.ticketworkflow.ticket.application.cursor.TicketTimelineCursorCodec;
import dev.opsmind.ticketworkflow.ticket.application.cursor.TicketTimelineCursorSigner;
import dev.opsmind.ticketworkflow.ticket.application.exception.TicketAuthorizationException;
import dev.opsmind.ticketworkflow.ticket.application.observability.TicketTelemetry;
import dev.opsmind.ticketworkflow.ticket.application.policy.TicketTimelineViewPolicy;
import dev.opsmind.ticketworkflow.ticket.application.port.out.ClockPort;
import dev.opsmind.ticketworkflow.ticket.application.port.out.SensitiveReadAuditPort;
import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketTimelineQueryPort;
import dev.opsmind.ticketworkflow.ticket.application.query.TicketTimelineQuery;
import dev.opsmind.ticketworkflow.ticket.application.service.GetTicketTimelineApplicationService;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketId;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * SPEC-TW-006 §21: Auditor resolves to {@code AUDITOR_POLICY_VIEW} but is
 * always denied today — mirrors Get Ticket's {@code AUDITOR_VIEW} stub
 * (SPEC-TW-002), since no acceptance scenario in this spec exercises a
 * working Auditor policy and §21 itself describes it as a future
 * dedicated API. The query port must never be touched for an Auditor.
 */
@Tag("unit")
class AuditorTimelinePolicyTest {

    private static final Instant NOW = Instant.parse("2026-07-25T20:00:00Z");
    private static final UUID TICKET_ID = UUID.randomUUID();

    @Test
    void auditorShouldAlwaysBeDeniedWithoutTouchingTheQueryPort() {
        TicketTimelineQueryPort queryPort = mock(TicketTimelineQueryPort.class);
        ClockPort clock = mock(ClockPort.class);
        when(clock.now()).thenReturn(NOW);
        TicketWorkflowProperties properties = new TicketWorkflowProperties(
            "unit-test-secret", "unit-test-cursor-secret",
            new TicketWorkflowProperties.Sla("DEFAULT", Duration.ofHours(4), Duration.ofHours(24), Duration.ofHours(4))
        );
        TicketTimelineCursorCodec codec = new TicketTimelineCursorCodec(new ObjectMapper().findAndRegisterModules(), new TicketTimelineCursorSigner(properties));
        GetTicketTimelineApplicationService service = new GetTicketTimelineApplicationService(
            queryPort, codec, new TicketTimelineViewPolicy(), mock(SensitiveReadAuditPort.class), mock(TicketTelemetry.class), clock
        );

        TicketTimelineQuery query = new TicketTimelineQuery(
            TicketId.of(TICKET_ID), TicketTimelineFixtures.auditorActor("auditor-1"), Set.of(), 50, null
        );

        assertThatThrownBy(() -> service.getTimeline(query)).isInstanceOf(TicketAuthorizationException.class);
        verify(queryPort, never()).loadGuard(any());
        verify(queryPort, never()).queryTimeline(any(), org.mockito.ArgumentMatchers.anyBoolean(), any(), any(), org.mockito.ArgumentMatchers.anyInt());
    }
}
