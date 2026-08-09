package dev.opsmind.ticketworkflow.ticket.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.opsmind.ticketworkflow.ticket.application.command.ActorContext;
import dev.opsmind.ticketworkflow.ticket.application.command.CancelTicketCommand;
import dev.opsmind.ticketworkflow.ticket.application.command.CancelTicketResult;
import dev.opsmind.ticketworkflow.ticket.application.event.TicketCancelledEventMapper;
import dev.opsmind.ticketworkflow.ticket.application.command.StepUpProof;
import dev.opsmind.ticketworkflow.ticket.application.exception.IdempotencyKeyReusedException;
import dev.opsmind.ticketworkflow.ticket.application.exception.RequestInProgressException;
import dev.opsmind.ticketworkflow.ticket.application.exception.StepUpAuthenticationRequiredException;
import dev.opsmind.ticketworkflow.ticket.application.exception.TicketAuthorizationException;
import dev.opsmind.ticketworkflow.ticket.application.exception.TicketNotFoundException;
import dev.opsmind.ticketworkflow.ticket.application.exception.TicketVersionConflictException;
import dev.opsmind.ticketworkflow.ticket.application.idempotency.IdempotencyReservationOutcome;
import dev.opsmind.ticketworkflow.ticket.application.idempotency.RequestHashCalculator;
import dev.opsmind.ticketworkflow.ticket.application.model.AuditRecordEntry;
import dev.opsmind.ticketworkflow.ticket.application.model.OutboxEventEntry;
import dev.opsmind.ticketworkflow.ticket.application.model.TicketStatusHistoryEntry;
import dev.opsmind.ticketworkflow.ticket.application.observability.TicketTelemetry;
import dev.opsmind.ticketworkflow.ticket.application.policy.StepUpAuthenticationAuditRecorder;
import dev.opsmind.ticketworkflow.ticket.application.policy.StepUpAuthenticationPolicy;
import dev.opsmind.ticketworkflow.ticket.application.port.out.AuditRecordPort;
import dev.opsmind.ticketworkflow.ticket.application.port.out.ClockPort;
import dev.opsmind.ticketworkflow.ticket.application.port.out.IdempotencyRepository;
import dev.opsmind.ticketworkflow.ticket.application.port.out.OutboxEventRepository;
import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketCancelGuard;
import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketCancelGuardPort;
import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketCancelRepository;
import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketCancelUpdate;
import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketCancelUpdateOutcome;
import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketHistoryWriter;
import dev.opsmind.ticketworkflow.ticket.application.service.CancelTicketApplicationService;
import dev.opsmind.ticketworkflow.ticket.domain.exception.InvalidTicketStateException;
import dev.opsmind.ticketworkflow.ticket.domain.value.CancelReasonCode;
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

/** SPEC-TW-029: the full success transaction, guard rejections, and idempotency outcomes. Mirrors {@code ConfirmResolutionApplicationServiceTest}'s (SPEC-TW-026) shape. */
@Tag("unit")
class CancelTicketApplicationServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-07T22:00:00Z");
    private static final UUID TICKET_ID = UUID.fromString("6c2ad02e-c394-41fb-8e38-dfffd581a59d");
    private static final UUID RESOLUTION_CYCLE_ID = UUID.fromString("4bde946d-60b8-4e4e-9970-6a0d0d1448f1");
    private static final UUID SUPPORT_QUEUE_ID = UUID.fromString("9d38b723-4a4d-47d3-94fe-32ef78cc0690");
    private static final String REQUESTER_ID = "employee-123";
    private static final String ASSIGNEE_ID = "sam.support";
    private static final String REASON = "The requester no longer needs this request.";
    private static final StepUpProof VALID_STEP_UP_PROOF = new StepUpProof("proof-1", "MFA_TOTP", NOW.minusSeconds(60), NOW.plusSeconds(3600));

    private TicketCancelGuardPort guardPort;
    private TicketCancelRepository repository;
    private TicketHistoryWriter historyWriter;
    private AuditRecordPort auditRecordPort;
    private OutboxEventRepository outboxEventRepository;
    private IdempotencyRepository idempotencyRepository;
    private ClockPort clock;
    private TicketTelemetry telemetry;
    private StepUpAuthenticationAuditRecorder stepUpAuditRecorder;
    private CancelTicketApplicationService service;

    @BeforeEach
    void setUp() {
        guardPort = mock(TicketCancelGuardPort.class);
        repository = mock(TicketCancelRepository.class);
        historyWriter = mock(TicketHistoryWriter.class);
        auditRecordPort = mock(AuditRecordPort.class);
        outboxEventRepository = mock(OutboxEventRepository.class);
        idempotencyRepository = mock(IdempotencyRepository.class);
        clock = mock(ClockPort.class);
        telemetry = mock(TicketTelemetry.class);
        stepUpAuditRecorder = mock(StepUpAuthenticationAuditRecorder.class);

        when(clock.now()).thenReturn(NOW);
        when(idempotencyRepository.reserve(any())).thenReturn(new IdempotencyReservationOutcome.Reserved(UUID.randomUUID()));
        when(guardPort.loadGuard(any())).thenReturn(Optional.of(defaultGuard()));
        when(repository.applyCancel(any())).thenAnswer(invocation -> {
            TicketCancelUpdate update = invocation.getArgument(0);
            return new TicketCancelUpdateOutcome.Updated(update.expectedVersion() + 1);
        });

        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        service = new CancelTicketApplicationService(
            guardPort, repository, historyWriter, auditRecordPort, outboxEventRepository, idempotencyRepository,
            clock, new RequestHashCalculator(objectMapper), new TicketCancelledEventMapper(), telemetry, objectMapper,
            new StepUpAuthenticationPolicy(), stepUpAuditRecorder
        );
    }

    private TicketCancelGuard defaultGuard() {
        return guardInStatus(TicketStatus.IN_PROGRESS, 5L);
    }

    private TicketCancelGuard guardInStatus(TicketStatus status, long version) {
        return new TicketCancelGuard(
            TicketId.of(TICKET_ID), TicketDisplayId.of("INC-42"), REQUESTER_ID, status, version,
            SupportQueueId.of(SUPPORT_QUEUE_ID), ASSIGNEE_ID, RESOLUTION_CYCLE_ID
        );
    }

    private CancelTicketCommand employeeCommand(String idempotencyKey) {
        return new CancelTicketCommand(
            TicketId.of(TICKET_ID), CancelReasonCode.NO_LONGER_NEEDED, REASON, 5L,
            new ActorContext("EMPLOYEE", REQUESTER_ID, "employee-portal", Set.of("ticket:cancel")),
            idempotencyKey, "corr-1", "cmd-1", NOW, VALID_STEP_UP_PROOF
        );
    }

    @Test
    void shouldCancelSuccessfullyAndPersistHistoryAuditOutboxAndIdempotency() {
        CancelTicketResult result = service.cancel(employeeCommand("key-1"));

        assertThat(result.status()).isEqualTo(TicketStatus.CANCELLED);
        assertThat(result.previousStatus()).isEqualTo(TicketStatus.IN_PROGRESS);
        assertThat(result.cancelReasonCode()).isEqualTo(CancelReasonCode.NO_LONGER_NEEDED);
        assertThat(result.cancelledBy()).isEqualTo(REQUESTER_ID);
        assertThat(result.resolutionCycleId()).isEqualTo(RESOLUTION_CYCLE_ID);
        assertThat(result.version()).isEqualTo(6L);
        assertThat(result.replayed()).isFalse();

        ArgumentCaptor<TicketStatusHistoryEntry> historyCaptor = ArgumentCaptor.forClass(TicketStatusHistoryEntry.class);
        verify(historyWriter).append(historyCaptor.capture());
        assertThat(historyCaptor.getValue().fromStatus()).isEqualTo(TicketStatus.IN_PROGRESS);
        assertThat(historyCaptor.getValue().toStatus()).isEqualTo(TicketStatus.CANCELLED);
        assertThat(historyCaptor.getValue().transitionId()).isEqualTo("SM-034");
        assertThat(historyCaptor.getValue().reasonCode()).isEqualTo("TICKET_CANCELLED");
        assertThat(historyCaptor.getValue().aggregateVersion()).isEqualTo(6L);

        ArgumentCaptor<AuditRecordEntry> auditCaptor = ArgumentCaptor.forClass(AuditRecordEntry.class);
        verify(auditRecordPort).append(auditCaptor.capture());
        assertThat(auditCaptor.getValue().action()).isEqualTo("TICKET_CANCELLED");
        assertThat(auditCaptor.getValue().outcome()).isEqualTo("SUCCESS");

        ArgumentCaptor<OutboxEventEntry> outboxCaptor = ArgumentCaptor.forClass(OutboxEventEntry.class);
        verify(outboxEventRepository).append(outboxCaptor.capture());
        assertThat(outboxCaptor.getValue().eventType()).isEqualTo("ticket.cancelled");
        assertThat(outboxCaptor.getValue().routingKey()).isEqualTo("ticket.cancelled.v1");

        verify(idempotencyRepository).complete(any(), any());
        verify(telemetry).recordCancelCommand("success");
    }

    @Test
    void shouldCancelAnUnassignedNewTicket() {
        when(guardPort.loadGuard(any())).thenReturn(Optional.of(new TicketCancelGuard(
            TicketId.of(TICKET_ID), TicketDisplayId.of("INC-42"), REQUESTER_ID, TicketStatus.NEW, 5L,
            SupportQueueId.of(SUPPORT_QUEUE_ID), null, RESOLUTION_CYCLE_ID
        )));

        CancelTicketResult result = service.cancel(employeeCommand("key-1"));

        assertThat(result.previousStatus()).isEqualTo(TicketStatus.NEW);
        assertThat(result.status()).isEqualTo(TicketStatus.CANCELLED);
    }

    @Test
    void shouldAllowAnAuthorizedSupportActorRegardlessOfRequesterIdentity() {
        CancelTicketCommand command = new CancelTicketCommand(
            TicketId.of(TICKET_ID), CancelReasonCode.SUPPORT_CANCELLED, REASON, 5L,
            new ActorContext("IT_SUPPORT", "sam.support", "support-console", Set.of("ticket:cancel")),
            "key-support", "corr-1", "cmd-1", NOW, VALID_STEP_UP_PROOF
        );

        CancelTicketResult result = service.cancel(command);

        assertThat(result.status()).isEqualTo(TicketStatus.CANCELLED);
        assertThat(result.cancelReasonCode()).isEqualTo(CancelReasonCode.SUPPORT_CANCELLED);
    }

    @Test
    void shouldReturnOriginalResponseOnReplayWithoutAnySideEffects() {
        String storedJson = """
            {"ticketId":"%s","previousStatus":"IN_PROGRESS","status":"CANCELLED","cancelReasonCode":"NO_LONGER_NEEDED",\
            "cancelledBy":"%s","cancelledAt":"2026-08-07T22:00:00Z","resolutionCycleId":"%s","version":6}
            """.formatted(TICKET_ID, REQUESTER_ID, RESOLUTION_CYCLE_ID);
        when(idempotencyRepository.reserve(any())).thenReturn(new IdempotencyReservationOutcome.Replayed(200, storedJson));

        CancelTicketResult result = service.cancel(employeeCommand("same-key"));

        assertThat(result.replayed()).isTrue();
        assertThat(result.version()).isEqualTo(6L);
        verify(guardPort, never()).loadGuard(any());
        verify(repository, never()).applyCancel(any());
        verify(historyWriter, never()).append(any());
        verify(auditRecordPort, never()).append(any());
        verify(outboxEventRepository, never()).append(any());
        verify(telemetry).recordCancelCommand("replay");
    }

    @Test
    void shouldRejectSameIdempotencyKeyWithADifferentPayload() {
        when(idempotencyRepository.reserve(any())).thenReturn(new IdempotencyReservationOutcome.KeyReused());

        assertThatThrownBy(() -> service.cancel(employeeCommand("same-key"))).isInstanceOf(IdempotencyKeyReusedException.class);
        verify(repository, never()).applyCancel(any());
    }

    @Test
    void shouldRejectAFreshInProgressDuplicate() {
        when(idempotencyRepository.reserve(any())).thenReturn(new IdempotencyReservationOutcome.RequestInProgress());

        assertThatThrownBy(() -> service.cancel(employeeCommand("same-key"))).isInstanceOf(RequestInProgressException.class);
        verify(repository, never()).applyCancel(any());
    }

    @Test
    void shouldRejectAnActorWithoutTheRequiredScope() {
        CancelTicketCommand command = new CancelTicketCommand(
            TicketId.of(TICKET_ID), CancelReasonCode.NO_LONGER_NEEDED, REASON, 5L,
            new ActorContext("EMPLOYEE", REQUESTER_ID, "employee-portal", Set.of()),
            "key-1", "corr-1", "cmd-1", NOW, null
        );

        assertThatThrownBy(() -> service.cancel(command)).isInstanceOf(TicketAuthorizationException.class);
        verify(idempotencyRepository, never()).reserve(any());
    }

    @Test
    void shouldRejectAnEmployeeWhoIsNotTheTicketsRequester() {
        CancelTicketCommand command = new CancelTicketCommand(
            TicketId.of(TICKET_ID), CancelReasonCode.NO_LONGER_NEEDED, REASON, 5L,
            new ActorContext("EMPLOYEE", "someone-else", "employee-portal", Set.of("ticket:cancel")),
            "key-1", "corr-1", "cmd-1", NOW, null
        );

        assertThatThrownBy(() -> service.cancel(command)).isInstanceOf(TicketAuthorizationException.class);
        verify(repository, never()).applyCancel(any());
    }

    @Test
    void shouldReturn404WhenTheTicketDoesNotExist() {
        when(guardPort.loadGuard(any())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.cancel(employeeCommand("key-1"))).isInstanceOf(TicketNotFoundException.class);
    }

    @Test
    void shouldRejectAStaleExpectedVersion() {
        when(guardPort.loadGuard(any())).thenReturn(Optional.of(guardInStatus(TicketStatus.IN_PROGRESS, 6L)));

        assertThatThrownBy(() -> service.cancel(employeeCommand("key-1")))
            .isInstanceOfSatisfying(TicketVersionConflictException.class, ex -> assertThat(ex.currentVersion()).isEqualTo(6L));
        verify(repository, never()).applyCancel(any());
    }

    @Test
    void shouldRejectATerminalStatus() {
        when(guardPort.loadGuard(any())).thenReturn(Optional.of(guardInStatus(TicketStatus.CLOSED, 5L)));

        assertThatThrownBy(() -> service.cancel(employeeCommand("key-1"))).isInstanceOf(InvalidTicketStateException.class);
        verify(repository, never()).applyCancel(any());
    }

    @Test
    void shouldRejectAnInvalidStateDetectedAtTheRepositoryLayer() {
        doReturn(new TicketCancelUpdateOutcome.InvalidState(TicketStatus.CLOSED)).when(repository).applyCancel(any());

        assertThatThrownBy(() -> service.cancel(employeeCommand("key-1"))).isInstanceOf(InvalidTicketStateException.class);
    }

    @Test
    void shouldRejectATicketMissingRaceDetectedAtTheRepositoryLayer() {
        doReturn(new TicketCancelUpdateOutcome.TicketMissing()).when(repository).applyCancel(any());

        assertThatThrownBy(() -> service.cancel(employeeCommand("key-1"))).isInstanceOf(TicketNotFoundException.class);
    }

    @Test
    void shouldRejectAVersionMismatchRaceDetectedAtTheRepositoryLayer() {
        doReturn(new TicketCancelUpdateOutcome.VersionMismatch(99L)).when(repository).applyCancel(any());

        assertThatThrownBy(() -> service.cancel(employeeCommand("key-1")))
            .isInstanceOfSatisfying(TicketVersionConflictException.class, ex -> assertThat(ex.currentVersion()).isEqualTo(99L));
    }

    @Test
    void shouldRejectAResolutionCycleRaceDetectedAtTheRepositoryLayer() {
        doReturn(new TicketCancelUpdateOutcome.ResolutionCycleConflict()).when(repository).applyCancel(any());

        assertThatThrownBy(() -> service.cancel(employeeCommand("key-1"))).isInstanceOf(TicketNotFoundException.class);
    }

    /** SPEC-TW-036: Cancel is a phase-09 high-risk command — a missing step-up proof is rejected before the persistence mutation. */
    @Test
    void shouldRejectCancelWithoutAStepUpProof() {
        CancelTicketCommand command = new CancelTicketCommand(
            TicketId.of(TICKET_ID), CancelReasonCode.NO_LONGER_NEEDED, REASON, 5L,
            new ActorContext("EMPLOYEE", REQUESTER_ID, "employee-portal", Set.of("ticket:cancel")),
            "key-1", "corr-1", "cmd-1", NOW, null
        );

        assertThatThrownBy(() -> service.cancel(command)).isInstanceOf(StepUpAuthenticationRequiredException.class);
        verify(repository, never()).applyCancel(any());
    }

    @Test
    void shouldRejectCancelWithAnExpiredStepUpProof() {
        StepUpProof expiredProof = new StepUpProof("proof-1", "MFA_TOTP", NOW.minusSeconds(7200), NOW.minusSeconds(3600));
        CancelTicketCommand command = new CancelTicketCommand(
            TicketId.of(TICKET_ID), CancelReasonCode.NO_LONGER_NEEDED, REASON, 5L,
            new ActorContext("EMPLOYEE", REQUESTER_ID, "employee-portal", Set.of("ticket:cancel")),
            "key-1", "corr-1", "cmd-1", NOW, expiredProof
        );

        assertThatThrownBy(() -> service.cancel(command)).isInstanceOf(StepUpAuthenticationRequiredException.class);
        verify(repository, never()).applyCancel(any());
    }

    @Test
    void shouldAllowCancelWithAValidStepUpProof() {
        CancelTicketResult result = service.cancel(employeeCommand("key-1"));

        assertThat(result.status()).isEqualTo(TicketStatus.CANCELLED);
        verify(stepUpAuditRecorder).recordAllowed(any(), any(), any(), any(), any(), any());
    }
}
