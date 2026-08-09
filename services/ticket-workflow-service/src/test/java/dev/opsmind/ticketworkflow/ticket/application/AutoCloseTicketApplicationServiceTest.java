package dev.opsmind.ticketworkflow.ticket.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.opsmind.ticketworkflow.ticket.application.command.ActorContext;
import dev.opsmind.ticketworkflow.ticket.application.command.AutoCloseTicketCommand;
import dev.opsmind.ticketworkflow.ticket.application.command.AutoCloseTicketResult;
import dev.opsmind.ticketworkflow.ticket.application.event.TicketAutoClosedEventMapper;
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
import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketAutoCloseGuard;
import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketAutoCloseGuardPort;
import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketAutoCloseRepository;
import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketAutoCloseUpdate;
import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketAutoCloseUpdateOutcome;
import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketHistoryWriter;
import dev.opsmind.ticketworkflow.ticket.application.service.AutoCloseTicketApplicationService;
import dev.opsmind.ticketworkflow.ticket.domain.exception.AutoCloseNotYetDueException;
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
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** SPEC-TW-027: the full success transaction, guard rejections, eligibility recomputation, and idempotency outcomes. Mirrors {@code ConfirmResolutionApplicationServiceTest}'s (SPEC-TW-026) shape. */
@Tag("unit")
class AutoCloseTicketApplicationServiceTest {

    private static final Instant AUTO_CLOSE_DUE_AT = Instant.parse("2026-08-06T20:10:00Z");
    private static final Instant NOW = AUTO_CLOSE_DUE_AT.plusSeconds(60);
    private static final UUID TICKET_ID = UUID.fromString("6c2ad02e-c394-41fb-8e38-dfffd581a59d");
    private static final UUID RESOLUTION_CYCLE_ID = UUID.fromString("4bde946d-60b8-4e4e-9970-6a0d0d1448f1");
    private static final UUID SUPPORT_QUEUE_ID = UUID.fromString("9d38b723-4a4d-47d3-94fe-32ef78cc0690");
    private static final String ASSIGNEE_ID = "sam.support";
    private static final String REASON = "Auto-close policy window elapsed without further activity.";

    private TicketAutoCloseGuardPort guardPort;
    private TicketAutoCloseRepository repository;
    private TicketHistoryWriter historyWriter;
    private AuditRecordPort auditRecordPort;
    private OutboxEventRepository outboxEventRepository;
    private IdempotencyRepository idempotencyRepository;
    private ClockPort clock;
    private TicketTelemetry telemetry;
    private AutoCloseTicketApplicationService service;

    @BeforeEach
    void setUp() {
        guardPort = mock(TicketAutoCloseGuardPort.class);
        repository = mock(TicketAutoCloseRepository.class);
        historyWriter = mock(TicketHistoryWriter.class);
        auditRecordPort = mock(AuditRecordPort.class);
        outboxEventRepository = mock(OutboxEventRepository.class);
        idempotencyRepository = mock(IdempotencyRepository.class);
        clock = mock(ClockPort.class);
        telemetry = mock(TicketTelemetry.class);

        when(clock.now()).thenReturn(NOW);
        when(idempotencyRepository.reserve(any())).thenReturn(new IdempotencyReservationOutcome.Reserved(UUID.randomUUID()));
        when(guardPort.loadGuard(any())).thenReturn(Optional.of(defaultGuard()));
        when(repository.applyAutoClose(any())).thenAnswer(invocation -> {
            TicketAutoCloseUpdate update = invocation.getArgument(0);
            return new TicketAutoCloseUpdateOutcome.Updated(update.expectedVersion() + 1);
        });

        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        service = new AutoCloseTicketApplicationService(
            guardPort, repository, historyWriter, auditRecordPort, outboxEventRepository, idempotencyRepository,
            clock, new RequestHashCalculator(objectMapper), new TicketAutoClosedEventMapper(), telemetry, objectMapper
        );
    }

    private TicketAutoCloseGuard defaultGuard() {
        return guardWith(TicketStatus.RESOLVED, 18L, ResolutionCycleStatus.RESOLVED, AUTO_CLOSE_DUE_AT);
    }

    private TicketAutoCloseGuard guardWith(TicketStatus status, long version, ResolutionCycleStatus cycleStatus, Instant autoCloseDueAt) {
        return new TicketAutoCloseGuard(
            TicketId.of(TICKET_ID), TicketDisplayId.of("INC-42"), status, version,
            SupportQueueId.of(SUPPORT_QUEUE_ID), ASSIGNEE_ID, RESOLUTION_CYCLE_ID, cycleStatus, autoCloseDueAt
        );
    }

    private AutoCloseTicketCommand command(String idempotencyKey) {
        return new AutoCloseTicketCommand(
            TicketId.of(TICKET_ID), REASON, 18L,
            new ActorContext("SERVICE", "auto-close-scheduler", "auto-close-scheduler-service", Set.of("ticket:auto-close")),
            idempotencyKey, "corr-1", "cmd-1", NOW
        );
    }

    @Test
    void shouldAutoCloseSuccessfullyAndPersistHistoryAuditOutboxAndIdempotency() {
        AutoCloseTicketResult result = service.autoClose(command("key-1"));

        assertThat(result.status()).isEqualTo(TicketStatus.CLOSED);
        assertThat(result.previousStatus()).isEqualTo(TicketStatus.RESOLVED);
        assertThat(result.closeReasonCode()).isEqualTo(CloseReasonCode.AUTO_CLOSE_TIMEOUT);
        assertThat(result.closedBy()).isEqualTo("auto-close-scheduler");
        assertThat(result.resolutionCycleId()).isEqualTo(RESOLUTION_CYCLE_ID);
        assertThat(result.version()).isEqualTo(19L);
        assertThat(result.replayed()).isFalse();

        ArgumentCaptor<TicketStatusHistoryEntry> historyCaptor = ArgumentCaptor.forClass(TicketStatusHistoryEntry.class);
        verify(historyWriter).append(historyCaptor.capture());
        assertThat(historyCaptor.getValue().fromStatus()).isEqualTo(TicketStatus.RESOLVED);
        assertThat(historyCaptor.getValue().toStatus()).isEqualTo(TicketStatus.CLOSED);
        assertThat(historyCaptor.getValue().transitionId()).isEqualTo("SM-032");
        assertThat(historyCaptor.getValue().reasonCode()).isEqualTo("TICKET_AUTO_CLOSED");
        assertThat(historyCaptor.getValue().aggregateVersion()).isEqualTo(19L);

        ArgumentCaptor<AuditRecordEntry> auditCaptor = ArgumentCaptor.forClass(AuditRecordEntry.class);
        verify(auditRecordPort).append(auditCaptor.capture());
        assertThat(auditCaptor.getValue().action()).isEqualTo("TICKET_AUTO_CLOSED");
        assertThat(auditCaptor.getValue().outcome()).isEqualTo("SUCCESS");

        ArgumentCaptor<OutboxEventEntry> outboxCaptor = ArgumentCaptor.forClass(OutboxEventEntry.class);
        verify(outboxEventRepository).append(outboxCaptor.capture());
        assertThat(outboxCaptor.getValue().eventType()).isEqualTo("ticket.auto-closed");
        assertThat(outboxCaptor.getValue().routingKey()).isEqualTo("ticket.auto-closed.v1");

        verify(idempotencyRepository).complete(any(), any());
        verify(telemetry).recordAutoCloseCommand("success");
    }

    @Test
    void shouldReturnOriginalResponseOnReplayWithoutAnySideEffects() {
        String storedJson = """
            {"ticketId":"%s","previousStatus":"RESOLVED","status":"CLOSED","closeReasonCode":"AUTO_CLOSE_TIMEOUT",\
            "closedBy":"auto-close-scheduler","closedAt":"2026-08-06T20:11:00Z","resolutionCycleId":"%s","version":19}
            """.formatted(TICKET_ID, RESOLUTION_CYCLE_ID);
        when(idempotencyRepository.reserve(any())).thenReturn(new IdempotencyReservationOutcome.Replayed(200, storedJson));

        AutoCloseTicketResult result = service.autoClose(command("same-key"));

        assertThat(result.replayed()).isTrue();
        assertThat(result.version()).isEqualTo(19L);
        verify(guardPort, never()).loadGuard(any());
        verify(repository, never()).applyAutoClose(any());
        verify(historyWriter, never()).append(any());
        verify(auditRecordPort, never()).append(any());
        verify(outboxEventRepository, never()).append(any());
        verify(telemetry).recordAutoCloseCommand("replay");
    }

    @Test
    void shouldRejectSameIdempotencyKeyWithADifferentPayload() {
        when(idempotencyRepository.reserve(any())).thenReturn(new IdempotencyReservationOutcome.KeyReused());

        assertThatThrownBy(() -> service.autoClose(command("same-key"))).isInstanceOf(IdempotencyKeyReusedException.class);
        verify(repository, never()).applyAutoClose(any());
    }

    @Test
    void shouldRejectAFreshInProgressDuplicate() {
        when(idempotencyRepository.reserve(any())).thenReturn(new IdempotencyReservationOutcome.RequestInProgress());

        assertThatThrownBy(() -> service.autoClose(command("same-key"))).isInstanceOf(RequestInProgressException.class);
        verify(repository, never()).applyAutoClose(any());
    }

    @Test
    void shouldRejectAnActorWithoutTheRequiredScope() {
        AutoCloseTicketCommand command = new AutoCloseTicketCommand(
            TicketId.of(TICKET_ID), REASON, 18L,
            new ActorContext("SERVICE", "auto-close-scheduler", "auto-close-scheduler-service", Set.of()),
            "key-1", "corr-1", "cmd-1", NOW
        );

        assertThatThrownBy(() -> service.autoClose(command)).isInstanceOf(TicketAuthorizationException.class);
        verify(idempotencyRepository, never()).reserve(any());
    }

    @Test
    void shouldRejectANonServiceActorEvenWithTheRequiredScope() {
        AutoCloseTicketCommand command = new AutoCloseTicketCommand(
            TicketId.of(TICKET_ID), REASON, 18L,
            new ActorContext("IT_SUPPORT", "sam.support", "support-console", Set.of("ticket:auto-close")),
            "key-1", "corr-1", "cmd-1", NOW
        );

        assertThatThrownBy(() -> service.autoClose(command)).isInstanceOf(TicketAuthorizationException.class);
        verify(idempotencyRepository, never()).reserve(any());
    }

    @Test
    void shouldReturn404WhenTheTicketDoesNotExist() {
        when(guardPort.loadGuard(any())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.autoClose(command("key-1"))).isInstanceOf(TicketNotFoundException.class);
    }

    @Test
    void shouldRejectAStaleExpectedVersion() {
        when(guardPort.loadGuard(any())).thenReturn(Optional.of(guardWith(TicketStatus.RESOLVED, 19L, ResolutionCycleStatus.RESOLVED, AUTO_CLOSE_DUE_AT)));

        assertThatThrownBy(() -> service.autoClose(command("key-1")))
            .isInstanceOfSatisfying(TicketVersionConflictException.class, ex -> assertThat(ex.currentVersion()).isEqualTo(19L));
        verify(repository, never()).applyAutoClose(any());
    }

    @Test
    void shouldRejectANonResolvedStatus() {
        when(guardPort.loadGuard(any())).thenReturn(Optional.of(guardWith(TicketStatus.IN_PROGRESS, 18L, ResolutionCycleStatus.ACTIVE, null)));

        assertThatThrownBy(() -> service.autoClose(command("key-1")))
            .isInstanceOfSatisfying(InvalidStatusTransitionException.class, ex -> {
                assertThat(ex.currentStatus()).isEqualTo(TicketStatus.IN_PROGRESS);
                assertThat(ex.targetStatus()).isEqualTo(TicketStatus.CLOSED);
            });
    }

    @Test
    void shouldRejectAResolutionCycleThatIsNotYetResolved() {
        when(guardPort.loadGuard(any())).thenReturn(Optional.of(guardWith(TicketStatus.RESOLVED, 18L, ResolutionCycleStatus.ACTIVE, AUTO_CLOSE_DUE_AT)));

        assertThatThrownBy(() -> service.autoClose(command("key-1"))).isInstanceOf(ResolutionCycleNotFoundException.class);
        verify(repository, never()).applyAutoClose(any());
    }

    @Test
    void shouldRejectWhenTheDueDateHasNotYetPassed() {
        when(guardPort.loadGuard(any())).thenReturn(Optional.of(guardWith(TicketStatus.RESOLVED, 18L, ResolutionCycleStatus.RESOLVED, NOW.plusSeconds(3600))));

        assertThatThrownBy(() -> service.autoClose(command("key-1"))).isInstanceOf(AutoCloseNotYetDueException.class);
        verify(repository, never()).applyAutoClose(any());
    }

    @Test
    void shouldRejectWhenTheDueDateIsMissingEntirely() {
        when(guardPort.loadGuard(any())).thenReturn(Optional.of(guardWith(TicketStatus.RESOLVED, 18L, ResolutionCycleStatus.RESOLVED, null)));

        assertThatThrownBy(() -> service.autoClose(command("key-1"))).isInstanceOf(AutoCloseNotYetDueException.class);
        verify(repository, never()).applyAutoClose(any());
    }

    @Test
    void shouldRejectAnInvalidStateDetectedAtTheRepositoryLayer() {
        doReturn(new TicketAutoCloseUpdateOutcome.InvalidState(TicketStatus.IN_PROGRESS)).when(repository).applyAutoClose(any());

        assertThatThrownBy(() -> service.autoClose(command("key-1"))).isInstanceOf(InvalidStatusTransitionException.class);
    }

    @Test
    void shouldRejectAResolutionCycleRaceDetectedAtTheRepositoryLayer() {
        doReturn(new TicketAutoCloseUpdateOutcome.ResolutionCycleConflict()).when(repository).applyAutoClose(any());

        assertThatThrownBy(() -> service.autoClose(command("key-1"))).isInstanceOf(ResolutionCycleNotFoundException.class);
    }

    @Test
    void shouldRejectATicketMissingRaceDetectedAtTheRepositoryLayer() {
        doReturn(new TicketAutoCloseUpdateOutcome.TicketMissing()).when(repository).applyAutoClose(any());

        assertThatThrownBy(() -> service.autoClose(command("key-1"))).isInstanceOf(TicketNotFoundException.class);
    }

    @Test
    void shouldRejectAVersionMismatchRaceDetectedAtTheRepositoryLayer() {
        doReturn(new TicketAutoCloseUpdateOutcome.VersionMismatch(99L)).when(repository).applyAutoClose(any());

        assertThatThrownBy(() -> service.autoClose(command("key-1")))
            .isInstanceOfSatisfying(TicketVersionConflictException.class, ex -> assertThat(ex.currentVersion()).isEqualTo(99L));
    }
}
