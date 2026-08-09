package dev.opsmind.ticketworkflow.ticket.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.opsmind.ticketworkflow.ticket.application.command.ActorContext;
import dev.opsmind.ticketworkflow.ticket.application.command.ConfirmResolutionCommand;
import dev.opsmind.ticketworkflow.ticket.application.command.ConfirmResolutionResult;
import dev.opsmind.ticketworkflow.ticket.application.event.TicketResolutionConfirmedEventMapper;
import dev.opsmind.ticketworkflow.ticket.application.exception.IdempotencyKeyReusedException;
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
import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketHistoryWriter;
import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketResolutionConfirmationGuard;
import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketResolutionConfirmationGuardPort;
import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketResolutionConfirmationRepository;
import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketResolutionConfirmationUpdate;
import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketResolutionConfirmationUpdateOutcome;
import dev.opsmind.ticketworkflow.ticket.application.service.ConfirmResolutionApplicationService;
import dev.opsmind.ticketworkflow.ticket.domain.exception.InvalidStatusTransitionException;
import dev.opsmind.ticketworkflow.ticket.domain.value.ResolutionConfirmationReasonCode;
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
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** SPEC-TW-026: the full success transaction, guard rejections, and idempotency outcomes. Mirrors {@code CloseTicketApplicationServiceTest}'s (SPEC-TW-011) shape. */
@Tag("unit")
class ConfirmResolutionApplicationServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-06T20:10:00Z");
    private static final UUID TICKET_ID = UUID.fromString("6c2ad02e-c394-41fb-8e38-dfffd581a59d");
    private static final UUID RESOLUTION_CYCLE_ID = UUID.fromString("4bde946d-60b8-4e4e-9970-6a0d0d1448f1");
    private static final UUID SUPPORT_QUEUE_ID = UUID.fromString("9d38b723-4a4d-47d3-94fe-32ef78cc0690");
    private static final String REQUESTER_ID = "employee-123";
    private static final String ASSIGNEE_ID = "sam.support";
    private static final String REASON = "Requester confirmed the issue is resolved and no further action is required.";

    private TicketResolutionConfirmationGuardPort guardPort;
    private TicketResolutionConfirmationRepository repository;
    private TicketHistoryWriter historyWriter;
    private AuditRecordPort auditRecordPort;
    private OutboxEventRepository outboxEventRepository;
    private IdempotencyRepository idempotencyRepository;
    private ClockPort clock;
    private TicketTelemetry telemetry;
    private ConfirmResolutionApplicationService service;

    @BeforeEach
    void setUp() {
        guardPort = mock(TicketResolutionConfirmationGuardPort.class);
        repository = mock(TicketResolutionConfirmationRepository.class);
        historyWriter = mock(TicketHistoryWriter.class);
        auditRecordPort = mock(AuditRecordPort.class);
        outboxEventRepository = mock(OutboxEventRepository.class);
        idempotencyRepository = mock(IdempotencyRepository.class);
        clock = mock(ClockPort.class);
        telemetry = mock(TicketTelemetry.class);

        when(clock.now()).thenReturn(NOW);
        when(idempotencyRepository.reserve(any())).thenReturn(new IdempotencyReservationOutcome.Reserved(UUID.randomUUID()));
        when(guardPort.loadGuard(any())).thenReturn(Optional.of(defaultGuard()));
        when(repository.applyConfirmation(any())).thenAnswer(invocation -> {
            TicketResolutionConfirmationUpdate update = invocation.getArgument(0);
            return new TicketResolutionConfirmationUpdateOutcome.Updated(update.expectedVersion() + 1);
        });

        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        service = new ConfirmResolutionApplicationService(
            guardPort, repository, historyWriter, auditRecordPort, outboxEventRepository, idempotencyRepository,
            clock, new RequestHashCalculator(objectMapper), new TicketResolutionConfirmedEventMapper(), telemetry, objectMapper
        );
    }

    private TicketResolutionConfirmationGuard defaultGuard() {
        return guardInStatus(TicketStatus.RESOLVED, 18L, ResolutionCycleStatus.RESOLVED);
    }

    private TicketResolutionConfirmationGuard guardInStatus(TicketStatus status, long version, ResolutionCycleStatus cycleStatus) {
        return new TicketResolutionConfirmationGuard(
            TicketId.of(TICKET_ID), TicketDisplayId.of("INC-42"), REQUESTER_ID, status, version,
            SupportQueueId.of(SUPPORT_QUEUE_ID), ASSIGNEE_ID, RESOLUTION_CYCLE_ID, cycleStatus
        );
    }

    /** The primary caller: the ticket's own requester. */
    private ConfirmResolutionCommand employeeCommand(String idempotencyKey) {
        return new ConfirmResolutionCommand(
            TicketId.of(TICKET_ID), ResolutionConfirmationReasonCode.REQUESTER_CONFIRMED, REASON, 18L,
            new ActorContext("EMPLOYEE", REQUESTER_ID, "employee-portal", Set.of("ticket:resolution-confirm")),
            idempotencyKey, "corr-1", "cmd-1", NOW
        );
    }

    @Test
    void shouldConfirmSuccessfullyAndPersistHistoryAuditOutboxAndIdempotency() {
        ConfirmResolutionResult result = service.confirmResolution(employeeCommand("key-1"));

        assertThat(result.status()).isEqualTo(TicketStatus.CLOSED);
        assertThat(result.previousStatus()).isEqualTo(TicketStatus.RESOLVED);
        assertThat(result.reasonCode()).isEqualTo(ResolutionConfirmationReasonCode.REQUESTER_CONFIRMED);
        assertThat(result.confirmedBy()).isEqualTo(REQUESTER_ID);
        assertThat(result.resolutionCycleId()).isEqualTo(RESOLUTION_CYCLE_ID);
        assertThat(result.version()).isEqualTo(19L);
        assertThat(result.replayed()).isFalse();

        ArgumentCaptor<TicketStatusHistoryEntry> historyCaptor = ArgumentCaptor.forClass(TicketStatusHistoryEntry.class);
        verify(historyWriter).append(historyCaptor.capture());
        assertThat(historyCaptor.getValue().fromStatus()).isEqualTo(TicketStatus.RESOLVED);
        assertThat(historyCaptor.getValue().toStatus()).isEqualTo(TicketStatus.CLOSED);
        assertThat(historyCaptor.getValue().transitionId()).isEqualTo("SM-031");
        assertThat(historyCaptor.getValue().reasonCode()).isEqualTo("RESOLUTION_CONFIRMED");
        assertThat(historyCaptor.getValue().aggregateVersion()).isEqualTo(19L);

        ArgumentCaptor<AuditRecordEntry> auditCaptor = ArgumentCaptor.forClass(AuditRecordEntry.class);
        verify(auditRecordPort).append(auditCaptor.capture());
        assertThat(auditCaptor.getValue().action()).isEqualTo("RESOLUTION_CONFIRMED");
        assertThat(auditCaptor.getValue().outcome()).isEqualTo("SUCCESS");

        ArgumentCaptor<OutboxEventEntry> outboxCaptor = ArgumentCaptor.forClass(OutboxEventEntry.class);
        verify(outboxEventRepository).append(outboxCaptor.capture());
        assertThat(outboxCaptor.getValue().eventType()).isEqualTo("ticket.resolution-confirmed");
        assertThat(outboxCaptor.getValue().routingKey()).isEqualTo("ticket.resolution-confirmed.v1");

        verify(idempotencyRepository).complete(any(), any());
        verify(telemetry).recordConfirmResolutionCommand("success");
    }

    @Test
    void shouldAllowAnAuthorizedSupportActorRegardlessOfRequesterIdentity() {
        ConfirmResolutionCommand command = new ConfirmResolutionCommand(
            TicketId.of(TICKET_ID), ResolutionConfirmationReasonCode.SUPPORT_CONFIRMED, REASON, 18L,
            new ActorContext("IT_SUPPORT", "sam.support", "support-console", Set.of("ticket:resolution-confirm")),
            "key-support", "corr-1", "cmd-1", NOW
        );

        ConfirmResolutionResult result = service.confirmResolution(command);

        assertThat(result.status()).isEqualTo(TicketStatus.CLOSED);
        assertThat(result.reasonCode()).isEqualTo(ResolutionConfirmationReasonCode.SUPPORT_CONFIRMED);
    }

    @Test
    void shouldReturnOriginalResponseOnReplayWithoutAnySideEffects() {
        String storedJson = """
            {"ticketId":"%s","previousStatus":"RESOLVED","status":"CLOSED","reasonCode":"REQUESTER_CONFIRMED",\
            "confirmedBy":"%s","confirmedAt":"2026-08-06T20:10:00Z","resolutionCycleId":"%s","version":19}
            """.formatted(TICKET_ID, REQUESTER_ID, RESOLUTION_CYCLE_ID);
        when(idempotencyRepository.reserve(any())).thenReturn(new IdempotencyReservationOutcome.Replayed(200, storedJson));

        ConfirmResolutionResult result = service.confirmResolution(employeeCommand("same-key"));

        assertThat(result.replayed()).isTrue();
        assertThat(result.version()).isEqualTo(19L);
        verify(guardPort, never()).loadGuard(any());
        verify(repository, never()).applyConfirmation(any());
        verify(historyWriter, never()).append(any());
        verify(auditRecordPort, never()).append(any());
        verify(outboxEventRepository, never()).append(any());
        verify(telemetry).recordConfirmResolutionCommand("replay");
    }

    @Test
    void shouldRejectSameIdempotencyKeyWithADifferentPayload() {
        when(idempotencyRepository.reserve(any())).thenReturn(new IdempotencyReservationOutcome.KeyReused());

        assertThatThrownBy(() -> service.confirmResolution(employeeCommand("same-key"))).isInstanceOf(IdempotencyKeyReusedException.class);
        verify(repository, never()).applyConfirmation(any());
    }

    @Test
    void shouldRejectAFreshInProgressDuplicate() {
        when(idempotencyRepository.reserve(any())).thenReturn(new IdempotencyReservationOutcome.RequestInProgress());

        assertThatThrownBy(() -> service.confirmResolution(employeeCommand("same-key"))).isInstanceOf(RequestInProgressException.class);
        verify(repository, never()).applyConfirmation(any());
    }

    @Test
    void shouldRejectAnActorWithoutTheRequiredScope() {
        ConfirmResolutionCommand command = new ConfirmResolutionCommand(
            TicketId.of(TICKET_ID), ResolutionConfirmationReasonCode.REQUESTER_CONFIRMED, REASON, 18L,
            new ActorContext("EMPLOYEE", REQUESTER_ID, "employee-portal", Set.of()),
            "key-1", "corr-1", "cmd-1", NOW
        );

        assertThatThrownBy(() -> service.confirmResolution(command)).isInstanceOf(TicketAuthorizationException.class);
        verify(idempotencyRepository, never()).reserve(any());
    }

    @Test
    void shouldRejectAnEmployeeWhoIsNotTheTicketsRequester() {
        ConfirmResolutionCommand command = new ConfirmResolutionCommand(
            TicketId.of(TICKET_ID), ResolutionConfirmationReasonCode.REQUESTER_CONFIRMED, REASON, 18L,
            new ActorContext("EMPLOYEE", "someone-else", "employee-portal", Set.of("ticket:resolution-confirm")),
            "key-1", "corr-1", "cmd-1", NOW
        );

        assertThatThrownBy(() -> service.confirmResolution(command)).isInstanceOf(TicketAuthorizationException.class);
        verify(repository, never()).applyConfirmation(any());
    }

    @Test
    void shouldReturn404WhenTheTicketDoesNotExist() {
        when(guardPort.loadGuard(any())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.confirmResolution(employeeCommand("key-1"))).isInstanceOf(TicketNotFoundException.class);
    }

    @Test
    void shouldRejectAStaleExpectedVersion() {
        when(guardPort.loadGuard(any())).thenReturn(Optional.of(guardInStatus(TicketStatus.RESOLVED, 19L, ResolutionCycleStatus.RESOLVED)));

        assertThatThrownBy(() -> service.confirmResolution(employeeCommand("key-1")))
            .isInstanceOfSatisfying(TicketVersionConflictException.class, ex -> assertThat(ex.currentVersion()).isEqualTo(19L));
        verify(repository, never()).applyConfirmation(any());
    }

    @Test
    void shouldRejectANonResolvedStatus() {
        when(guardPort.loadGuard(any())).thenReturn(Optional.of(guardInStatus(TicketStatus.IN_PROGRESS, 18L, ResolutionCycleStatus.ACTIVE)));

        assertThatThrownBy(() -> service.confirmResolution(employeeCommand("key-1")))
            .isInstanceOfSatisfying(InvalidStatusTransitionException.class, ex -> {
                assertThat(ex.currentStatus()).isEqualTo(TicketStatus.IN_PROGRESS);
                assertThat(ex.targetStatus()).isEqualTo(TicketStatus.CLOSED);
            });
    }

    @Test
    void shouldRejectAResolutionCycleThatIsNotYetResolved() {
        when(guardPort.loadGuard(any())).thenReturn(Optional.of(guardInStatus(TicketStatus.RESOLVED, 18L, ResolutionCycleStatus.ACTIVE)));

        assertThatThrownBy(() -> service.confirmResolution(employeeCommand("key-1"))).isInstanceOf(ResolutionCycleNotFoundException.class);
        verify(repository, never()).applyConfirmation(any());
    }

    @Test
    void shouldRejectAnInvalidStateDetectedAtTheRepositoryLayer() {
        doReturn(new TicketResolutionConfirmationUpdateOutcome.InvalidState(TicketStatus.IN_PROGRESS)).when(repository).applyConfirmation(any());

        assertThatThrownBy(() -> service.confirmResolution(employeeCommand("key-1"))).isInstanceOf(InvalidStatusTransitionException.class);
    }

    @Test
    void shouldRejectAResolutionCycleRaceDetectedAtTheRepositoryLayer() {
        doReturn(new TicketResolutionConfirmationUpdateOutcome.ResolutionCycleConflict()).when(repository).applyConfirmation(any());

        assertThatThrownBy(() -> service.confirmResolution(employeeCommand("key-1"))).isInstanceOf(ResolutionCycleNotFoundException.class);
    }

    @Test
    void shouldRejectATicketMissingRaceDetectedAtTheRepositoryLayer() {
        doReturn(new TicketResolutionConfirmationUpdateOutcome.TicketMissing()).when(repository).applyConfirmation(any());

        assertThatThrownBy(() -> service.confirmResolution(employeeCommand("key-1"))).isInstanceOf(TicketNotFoundException.class);
    }

    @Test
    void shouldRejectAVersionMismatchRaceDetectedAtTheRepositoryLayer() {
        doReturn(new TicketResolutionConfirmationUpdateOutcome.VersionMismatch(99L)).when(repository).applyConfirmation(any());

        assertThatThrownBy(() -> service.confirmResolution(employeeCommand("key-1")))
            .isInstanceOfSatisfying(TicketVersionConflictException.class, ex -> assertThat(ex.currentVersion()).isEqualTo(99L));
    }
}
