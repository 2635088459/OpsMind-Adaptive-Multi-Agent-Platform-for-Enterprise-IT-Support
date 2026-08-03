package dev.opsmind.ticketworkflow.ticket.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.opsmind.ticketworkflow.configuration.TicketWorkflowProperties;
import dev.opsmind.ticketworkflow.support.TicketTimelineFixtures;
import dev.opsmind.ticketworkflow.ticket.application.cursor.TicketTimelineCursorCodec;
import dev.opsmind.ticketworkflow.ticket.application.cursor.TicketTimelineCursorSigner;
import dev.opsmind.ticketworkflow.ticket.application.exception.TicketNotFoundException;
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
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * SPEC-TW-006 §6: a Ticket that does not exist and a Ticket the actor is
 * not authorized for must be indistinguishable to the caller — both a
 * missing guard row and a guard row that fails the ownership/scope check
 * throw the exact same {@link TicketNotFoundException}, carrying no
 * detail that would let a caller infer "it exists but I lack access".
 */
@Tag("unit")
class TicketTimelineResourceHidingTest {

    private static final Instant NOW = Instant.parse("2026-07-25T20:00:00Z");
    private static final UUID TICKET_ID = UUID.randomUUID();

    private GetTicketTimelineApplicationService serviceWith(TicketTimelineQueryPort queryPort) {
        ClockPort clock = mock(ClockPort.class);
        when(clock.now()).thenReturn(NOW);
        TicketWorkflowProperties properties = new TicketWorkflowProperties(
            "unit-test-secret", "unit-test-cursor-secret",
            new TicketWorkflowProperties.Sla("DEFAULT", Duration.ofHours(4), Duration.ofHours(24), Duration.ofHours(4), Duration.ofDays(7))
        );
        TicketTimelineCursorCodec codec = new TicketTimelineCursorCodec(new ObjectMapper().findAndRegisterModules(), new TicketTimelineCursorSigner(properties));
        return new GetTicketTimelineApplicationService(
            queryPort, codec, new TicketTimelineViewPolicy(), mock(SensitiveReadAuditPort.class), mock(TicketTelemetry.class), clock
        );
    }

    @Test
    void missingTicketAndUnauthorizedTicketShouldThrowTheSameExceptionType() {
        TicketTimelineQueryPort missingPort = mock(TicketTimelineQueryPort.class);
        when(missingPort.loadGuard(any())).thenReturn(Optional.empty());

        TicketTimelineQueryPort unauthorizedPort = mock(TicketTimelineQueryPort.class);
        when(unauthorizedPort.loadGuard(any())).thenReturn(Optional.of(TicketTimelineFixtures.guard("employee-999", TicketTimelineFixtures.DEFAULT_APPLICATION_CODE)));

        TicketTimelineQuery query = new TicketTimelineQuery(
            TicketId.of(TICKET_ID), TicketTimelineFixtures.employeeActor("employee-123"), Set.of(), 50, null
        );

        Throwable missingFailure = catchThrowable(() -> serviceWith(missingPort).getTimeline(query));
        Throwable unauthorizedFailure = catchThrowable(() -> serviceWith(unauthorizedPort).getTimeline(query));

        assertThatThrownBy(() -> {
            throw missingFailure;
        }).isInstanceOf(TicketNotFoundException.class);
        assertThatThrownBy(() -> {
            throw unauthorizedFailure;
        }).isInstanceOf(TicketNotFoundException.class);
        org.assertj.core.api.Assertions.assertThat(missingFailure.getMessage()).isEqualTo(unauthorizedFailure.getMessage());
    }

    private static Throwable catchThrowable(org.assertj.core.api.ThrowableAssert.ThrowingCallable callable) {
        return org.assertj.core.api.Assertions.catchThrowable(callable);
    }
}
