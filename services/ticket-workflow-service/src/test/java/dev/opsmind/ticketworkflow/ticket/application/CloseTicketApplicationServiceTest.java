package dev.opsmind.ticketworkflow.ticket.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.opsmind.ticketworkflow.ticket.application.command.ActorContext;
import dev.opsmind.ticketworkflow.ticket.application.command.CloseTicketCommand;
import dev.opsmind.ticketworkflow.ticket.application.command.CloseTicketResult;
import dev.opsmind.ticketworkflow.ticket.application.event.TicketClosedEventMapper;
import dev.opsmind.ticketworkflow.ticket.application.exception.IdempotencyKeyReusedException;
import dev.opsmind.ticketworkflow.ticket.application.exception.QueueAccessDeniedException;
import dev.opsmind.ticketworkflow.ticket.application.exception.RequestInProgressException;
import dev.opsmind.ticketworkflow.ticket.application.exception.ResolutionCycleNotFoundException;
import dev.opsmind.ticketworkflow.ticket.application.exception.TicketAuthorizationException;
import dev.opsmind.ticketworkflow.ticket.application.exception.TicketNotFoundException;
import dev.opsmind.ticketworkflow.ticket.application.exception.TicketVersionConflictException;
import dev.opsmind.ticketworkflow.ticket.application.idempotency.IdempotencyReservationOutcome;
import dev.opsmind.ticketworkflow.ticket.application.idempotency.RequestHashCalculator;
import dev.opsmind.ticketworkflow.ticket.application.model.AuditRecordEntry;
import dev.opsmind.ticketworkflow.ticket.application.model.OutboxEventEntry;
import dev.opsmind.ticketworkflow.ticket.application.model.TicketStatusHistoryEntry;
import dev.opsmind.ticketworkflow.ticket.application.observability.TicketTelemetry;
import dev.opsmind.ticketworkflow.ticket.application.port.out.AuditRecordPort;
import dev.opsmind.ticketworkflow.ticket.application.port.out.ClockPort;
import dev.opsmind.ticketworkflow.ticket.application.port.out.IdempotencyRepository;
import dev.opsmind.ticketworkflow.ticket.application.port.out.OutboxEventRepository;
import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketCloseRepository;
import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketCloseReopenGuard;
import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketCloseReopenGuardPort;
import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketCloseUpdate;
import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketCloseUpdateOutcome;
import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketHistoryWriter;
import dev.opsmind.ticketworkflow.ticket.application.service.CloseTicketApplicationService;
import dev.opsmind.ticketworkflow.ticket.domain.exception.InvalidStatusTransitionException;
import dev.opsmind.ticketworkflow.ticket.domain.value.CloseReasonCode;
import dev.opsmind.ticketworkflow.ticket.domain.value.ResolutionCycleStatus;
import dev.opsmind.ticketworkflow.ticket.domain.value.SupportQueueId;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketDisplayId;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketId;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** SPEC-TW-011: the full success transaction, guard rejections, and idempotency outcomes for Close. */
@Tag("unit")
class CloseTicketApplicationServiceTest {

    private static final Instant NOW = Instant.parse("2026-07-31T20:10:00Z");
    private static final UUID TICKET_ID = UUID.fromString("6c2ad02e-c394-41fb-8e38-dfffd581a59d");
    private static final UUID RESOLUTION_CYCLE_ID = UUID.fromString("4bde946d-60b8-4e4e-9970-6a0d0d1448f1");
    private static final UUID SUPPORT_QUEUE_ID = UUID.fromString("9d38b723-4a4d-47d3-94fe-32ef78cc0690");
    private static final String TEAM_ID = "team-endpoint";
    private static final String ASSIGNEE_ID = "sam.support";
    private static final String REASON = "Requester confirmed the issue is resolved and no further action is required.";

    private TicketCloseReopenGuardPort guardPort;
    private TicketCloseRepository closeRepository;
    private TicketHistoryWriter historyWriter;
    private AuditRecordPort auditRecordPort;
    private OutboxEventRepository outboxEventRepository;
    private IdempotencyRepository idempotencyRepository;
    private ClockPort clock;
    private TicketTelemetry telemetry;
    private CloseTicketApplicationService service;

    @BeforeEach
    void setUp() {
        guardPort = mock(TicketCloseReopenGuardPort.class);
        closeRepository = mock(TicketCloseRepository.class);
        historyWriter = mock(TicketHistoryWriter.class);
        auditRecordPort = mock(AuditRecordPort.class);
        outboxEventRepository = mock(OutboxEventRepository.class);
        idempotencyRepository = mock(IdempotencyRepository.class);
        clock = mock(ClockPort.class);
        telemetry = mock(TicketTelemetry.class);

        when(clock.now()).thenReturn(NOW);
        when(idempotencyRepository.reserve(any())).thenReturn(new IdempotencyReservationOutcome.Reserved(UUID.randomUUID()));
        when(guardPort.loadGuard(any())).thenReturn(Optional.of(defaultGuard()));
        when(closeRepository.applyClose(any())).thenAnswer(invocation -> {
            TicketCloseUpdate update = invocation.getArgument(0);
            return new TicketCloseUpdateOutcome.Updated(update.expectedVersion() + 1);
        });

        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        service = new CloseTicketApplicationService(
            guardPort, closeRepository, historyWriter, auditRecordPort, outboxEventRepository, idempotencyRepository,
            clock, new RequestHashCalculator(objectMapper), new TicketClosedEventMapper(), telemetry, objectMapper
        );
    }

    private TicketCloseReopenGuard defaultGuard() {
        return new TicketCloseReopenGuard(
            TicketId.of(TICKET_ID), TicketDisplayId.of("INC-42"), TicketStatus.RESOLVED, 18L,
            SupportQueueId.of(SUPPORT_QUEUE_ID), TEAM_ID, ASSIGNEE_ID, RESOLUTION_CYCLE_ID, ResolutionCycleStatus.RESOLVED, 1, 0, null
        );
    }

    private CloseTicketCommand command(String idempotencyKey) {
        return new CloseTicketCommand(
            TicketId.of(TICKET_ID), CloseReasonCode.REQUESTER_CONFIRMED, REASON, 18L,
            new ActorContext("IT_SUPPORT", "sam.support", "support-console", Set.of("ticket:close")),
            Set.of(TEAM_ID), idempotencyKey, "corr-1", "cmd-1", NOW
        );
    }

    @Test
    void shouldCloseSuccessfullyAndPersistHistoryAuditOutboxAndIdempotency() {
        CloseTicketResult result = service.close(command("key-1"));

        assertThat(result.status()).isEqualTo(TicketStatus.CLOSED);
        assertThat(result.previousStatus()).isEqualTo(TicketStatus.RESOLVED);
        assertThat(result.closeReasonCode()).isEqualTo(CloseReasonCode.REQUESTER_CONFIRMED);
        assertThat(result.closedBy()).isEqualTo("sam.support");
        assertThat(result.resolutionCycleId()).isEqualTo(RESOLUTION_CYCLE_ID);
        assertThat(result.version()).isEqualTo(19L);
        assertThat(result.replayed()).isFalse();

        ArgumentCaptor<TicketStatusHistoryEntry> historyCaptor = ArgumentCaptor.forClass(TicketStatusHistoryEntry.class);
        verify(historyWriter).append(historyCaptor.capture());
        assertThat(historyCaptor.getValue().fromStatus()).isEqualTo(TicketStatus.RESOLVED);
        assertThat(historyCaptor.getValue().toStatus()).isEqualTo(TicketStatus.CLOSED);
        assertThat(historyCaptor.getValue().transitionId()).isEqualTo("SM-011");
        assertThat(historyCaptor.getValue().reasonCode()).isEqualTo("TICKET_CLOSED");
        assertThat(historyCaptor.getValue().aggregateVersion()).isEqualTo(19L);

        ArgumentCaptor<AuditRecordEntry> auditCaptor = ArgumentCaptor.forClass(AuditRecordEntry.class);
        verify(auditRecordPort).append(auditCaptor.capture());
        assertThat(auditCaptor.getValue().action()).isEqualTo("TICKET_CLOSED");
        assertThat(auditCaptor.getValue().outcome()).isEqualTo("SUCCESS");

        ArgumentCaptor<OutboxEventEntry> outboxCaptor = ArgumentCaptor.forClass(OutboxEventEntry.class);
        verify(outboxEventRepository).append(outboxCaptor.capture());
        assertThat(outboxCaptor.getValue().eventType()).isEqualTo("ticket.closed");
        assertThat(outboxCaptor.getValue().routingKey()).isEqualTo("ticket.closed.v1");

        verify(idempotencyRepository).complete(any(), any());
        verify(telemetry).recordCloseCommand("success");
    }

    @Test
    void shouldReturnOriginalResponseOnReplayWithoutAnySideEffects() {
        String storedJson = """
            {"ticketId":"%s","previousStatus":"RESOLVED","status":"CLOSED","closeReasonCode":"REQUESTER_CONFIRMED",\
            "closedBy":"sam.support","closedAt":"2026-07-31T20:10:00Z","resolutionCycleId":"%s","version":19}
            """.formatted(TICKET_ID, RESOLUTION_CYCLE_ID);
        when(idempotencyRepository.reserve(any())).thenReturn(new IdempotencyReservationOutcome.Replayed(200, storedJson));

        CloseTicketResult result = service.close(command("same-key"));

        assertThat(result.replayed()).isTrue();
        assertThat(result.version()).isEqualTo(19L);
        verify(guardPort, never()).loadGuard(any());
        verify(closeRepository, never()).applyClose(any());
        verify(historyWriter, never()).append(any());
        verify(auditRecordPort, never()).append(any());
        verify(outboxEventRepository, never()).append(any());
        verify(telemetry).recordCloseCommand("replay");
    }

    @Test
    void shouldRejectSameIdempotencyKeyWithADifferentPayload() {
        when(idempotencyRepository.reserve(any())).thenReturn(new IdempotencyReservationOutcome.KeyReused());

        assertThatThrownBy(() -> service.close(command("same-key"))).isInstanceOf(IdempotencyKeyReusedException.class);
        verify(closeRepository, never()).applyClose(any());
    }

    @Test
    void shouldRejectAFreshInProgressDuplicate() {
        when(idempotencyRepository.reserve(any())).thenReturn(new IdempotencyReservationOutcome.RequestInProgress());

        assertThatThrownBy(() -> service.close(command("same-key"))).isInstanceOf(RequestInProgressException.class);
        verify(closeRepository, never()).applyClose(any());
    }

    @Test
    void shouldRejectAnActorWithoutTheRequiredScope() {
        CloseTicketCommand command = new CloseTicketCommand(
            TicketId.of(TICKET_ID), CloseReasonCode.REQUESTER_CONFIRMED, REASON, 18L,
            new ActorContext("IT_SUPPORT", "sam.support", "support-console", Set.of()),
            Set.of(TEAM_ID), "key-1", "corr-1", "cmd-1", NOW
        );

        assertThatThrownBy(() -> service.close(command)).isInstanceOf(TicketAuthorizationException.class);
        verify(idempotencyRepository, never()).reserve(any());
    }

    @Test
    void shouldRejectATicketOutsideTheActorsAuthorizedQueue() {
        CloseTicketCommand command = new CloseTicketCommand(
            TicketId.of(TICKET_ID), CloseReasonCode.REQUESTER_CONFIRMED, REASON, 18L,
            new ActorContext("IT_SUPPORT", "sam.support", "support-console", Set.of("ticket:close")),
            Set.of("some-other-team"), "key-1", "corr-1", "cmd-1", NOW
        );

        assertThatThrownBy(() -> service.close(command)).isInstanceOf(QueueAccessDeniedException.class);
        verify(closeRepository, never()).applyClose(any());
    }

    @Test
    void shouldReturn404WhenTheTicketDoesNotExist() {
        when(guardPort.loadGuard(any())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.close(command("key-1"))).isInstanceOf(TicketNotFoundException.class);
    }

    @Test
    void shouldRejectAStaleExpectedVersion() {
        when(guardPort.loadGuard(any())).thenReturn(Optional.of(new TicketCloseReopenGuard(
            TicketId.of(TICKET_ID), TicketDisplayId.of("INC-42"), TicketStatus.RESOLVED, 19L,
            SupportQueueId.of(SUPPORT_QUEUE_ID), TEAM_ID, ASSIGNEE_ID, RESOLUTION_CYCLE_ID, ResolutionCycleStatus.RESOLVED, 1, 0, null
        )));

        assertThatThrownBy(() -> service.close(command("key-1")))
            .isInstanceOfSatisfying(TicketVersionConflictException.class, ex -> assertThat(ex.currentVersion()).isEqualTo(19L));
        verify(closeRepository, never()).applyClose(any());
    }

    @Test
    void shouldRejectAResolutionCycleThatIsNotYetResolved() {
        when(guardPort.loadGuard(any())).thenReturn(Optional.of(new TicketCloseReopenGuard(
            TicketId.of(TICKET_ID), TicketDisplayId.of("INC-42"), TicketStatus.RESOLVED, 18L,
            SupportQueueId.of(SUPPORT_QUEUE_ID), TEAM_ID, ASSIGNEE_ID, RESOLUTION_CYCLE_ID, ResolutionCycleStatus.ACTIVE, 1, 0, null
        )));

        assertThatThrownBy(() -> service.close(command("key-1"))).isInstanceOf(ResolutionCycleNotFoundException.class);
        verify(closeRepository, never()).applyClose(any());
    }

    @Test
    void shouldRejectAnInvalidStateDetectedAtTheRepositoryLayer() {
        org.mockito.Mockito.doReturn(new TicketCloseUpdateOutcome.InvalidState(TicketStatus.IN_PROGRESS)).when(closeRepository).applyClose(any());

        assertThatThrownBy(() -> service.close(command("key-1"))).isInstanceOf(InvalidStatusTransitionException.class);
    }

    @Test
    void shouldRejectAResolutionCycleRaceDetectedAtTheRepositoryLayer() {
        org.mockito.Mockito.doReturn(new TicketCloseUpdateOutcome.ResolutionCycleConflict()).when(closeRepository).applyClose(any());

        assertThatThrownBy(() -> service.close(command("key-1"))).isInstanceOf(ResolutionCycleNotFoundException.class);
    }
}
