package dev.opsmind.ticketworkflow.ticket.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.opsmind.ticketworkflow.configuration.TicketWorkflowProperties;
import dev.opsmind.ticketworkflow.support.TicketTimelineFixtures;
import dev.opsmind.ticketworkflow.ticket.application.cursor.TicketTimelineCursorCodec;
import dev.opsmind.ticketworkflow.ticket.application.cursor.TicketTimelineCursorSigner;
import dev.opsmind.ticketworkflow.ticket.application.observability.TicketTelemetry;
import dev.opsmind.ticketworkflow.ticket.application.policy.SensitiveReadAuditDecisionRecorder;
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
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** SPEC-TW-006 §17/§23: Support with the internal scope reads internal rows and triggers exactly one required Audit. */
@Tag("unit")
class SupportInternalTimelineVisibilityTest {

    private static final Instant NOW = Instant.parse("2026-07-25T20:00:00Z");
    private static final UUID TICKET_ID = UUID.randomUUID();

    @Test
    void supportInternalQueryShouldPassIncludeInternalTrueAndAuditExactlyOnce() {
        TicketTimelineQueryPort queryPort = mock(TicketTimelineQueryPort.class);
        when(queryPort.loadGuard(any())).thenReturn(Optional.of(TicketTimelineFixtures.guard("employee-123", TicketTimelineFixtures.DEFAULT_APPLICATION_CODE)));
        when(queryPort.queryTimeline(any(), eq(true), any(), any(), anyInt())).thenReturn(List.of());

        ClockPort clock = mock(ClockPort.class);
        when(clock.now()).thenReturn(NOW);
        SensitiveReadAuditPort auditPort = mock(SensitiveReadAuditPort.class);
        TicketWorkflowProperties properties = new TicketWorkflowProperties(
            "unit-test-secret", "unit-test-cursor-secret",
            new TicketWorkflowProperties.Sla("DEFAULT", Duration.ofHours(4), Duration.ofHours(24), Duration.ofHours(4), Duration.ofDays(7))
        );
        TicketTimelineCursorCodec codec = new TicketTimelineCursorCodec(new ObjectMapper().findAndRegisterModules(), new TicketTimelineCursorSigner(properties));
        GetTicketTimelineApplicationService service = new GetTicketTimelineApplicationService(
            queryPort, codec, new TicketTimelineViewPolicy(), auditPort, mock(TicketTelemetry.class), clock
        , mock(SensitiveReadAuditDecisionRecorder.class));

        service.getTimeline(new TicketTimelineQuery(
            TicketId.of(TICKET_ID), TicketTimelineFixtures.supportInternalActor("support-100"),
            TicketTimelineFixtures.scope(TicketTimelineFixtures.DEFAULT_APPLICATION_CODE), 50, null
        ));

        verify(queryPort).queryTimeline(eq(TicketId.of(TICKET_ID)), eq(true), any(), any(), anyInt());
        verify(auditPort, org.mockito.Mockito.times(1)).recordSensitiveRead(any());
    }
}
