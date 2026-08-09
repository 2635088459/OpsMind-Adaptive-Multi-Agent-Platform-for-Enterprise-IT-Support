package dev.opsmind.ticketworkflow.ticket.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.opsmind.ticketworkflow.ticket.application.command.ActorContext;
import dev.opsmind.ticketworkflow.ticket.application.command.EscalateTicketCommand;
import dev.opsmind.ticketworkflow.ticket.application.command.EscalateTicketResult;
import dev.opsmind.ticketworkflow.ticket.application.event.TicketEscalatedEventMapper;
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
import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketEscalationGuard;
import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketEscalationGuardPort;
import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketEscalationRepository;
import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketEscalationUpdate;
import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketEscalationUpdateOutcome;
import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketHistoryWriter;
import dev.opsmind.ticketworkflow.ticket.application.service.EscalateTicketApplicationService;
import dev.opsmind.ticketworkflow.ticket.domain.exception.InvalidTicketStateException;
import dev.opsmind.ticketworkflow.ticket.domain.value.EscalationReasonCode;
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

/** SPEC-TW-031: the full success transaction, guard rejections, and idempotency outcomes. Mirrors {@code CancelTicketApplicationServiceTest}'s (SPEC-TW-029) shape. */
@Tag("unit")
class EscalateTicketApplicationServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-07T22:00:00Z");
    private static final UUID TICKET_ID = UUID.fromString("6c2ad02e-c394-41fb-8e38-dfffd581a59d");
    private static final UUID RESOLUTION_CYCLE_ID = UUID.fromString("4bde946d-60b8-4e4e-9970-6a0d0d1448f1");
    private static final UUID SUPPORT_QUEUE_ID = UUID.fromString("9d38b723-4a4d-47d3-94fe-32ef78cc0690");
    private static final String TEAM_ID = "TEAM-A";
    private static final String ASSIGNEE_ID = "sam.support";
    private static final String WORKFLOW_ID = "wf-42";
    private static final String REASON = "Customer-facing outage with broad user impact.";
    private static final StepUpProof VALID_STEP_UP_PROOF = new StepUpProof("proof-1", "MFA_TOTP", NOW.minusSeconds(60), NOW.plusSeconds(3600));

    private TicketEscalationGuardPort guardPort;
    private TicketEscalationRepository repository;
    private TicketHistoryWriter historyWriter;
    private AuditRecordPort auditRecordPort;
    private OutboxEventRepository outboxEventRepository;
    private IdempotencyRepository idempotencyRepository;
    private ClockPort clock;
    private TicketTelemetry telemetry;
    private StepUpAuthenticationAuditRecorder stepUpAuditRecorder;
    private EscalateTicketApplicationService service;

    @BeforeEach
    void setUp() {
        guardPort = mock(TicketEscalationGuardPort.class);
        repository = mock(TicketEscalationRepository.class);
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
        when(repository.applyEscalation(any())).thenAnswer(invocation -> {
            TicketEscalationUpdate update = invocation.getArgument(0);
            return new TicketEscalationUpdateOutcome.Updated(update.expectedVersion() + 1);
        });

        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        service = new EscalateTicketApplicationService(
            guardPort, repository, historyWriter, auditRecordPort, outboxEventRepository, idempotencyRepository,
            clock, new RequestHashCalculator(objectMapper), new TicketEscalatedEventMapper(), telemetry, objectMapper,
            new StepUpAuthenticationPolicy(), stepUpAuditRecorder
        );
    }

    private TicketEscalationGuard defaultGuard() {
        return guardInStatus(TicketStatus.IN_PROGRESS, 5L);
    }

    private TicketEscalationGuard guardInStatus(TicketStatus status, long version) {
        return new TicketEscalationGuard(
            TicketId.of(TICKET_ID), TicketDisplayId.of("INC-42"), status, version,
            TEAM_ID, SupportQueueId.of(SUPPORT_QUEUE_ID), ASSIGNEE_ID, RESOLUTION_CYCLE_ID, WORKFLOW_ID
        );
    }

    private EscalateTicketCommand command(String idempotencyKey) {
        return new EscalateTicketCommand(
            TicketId.of(TICKET_ID), EscalationReasonCode.USER_IMPACT, REASON, 5L,
            new ActorContext("IT_SUPPORT", "lead.sam", "support-console", Set.of("ticket:escalate")),
            idempotencyKey, "corr-1", "cmd-1", NOW, VALID_STEP_UP_PROOF
        );
    }

    @Test
    void shouldEscalateSuccessfullyAndPersistHistoryAuditOutboxAndIdempotency() {
        EscalateTicketResult result = service.escalate(command("key-1"));

        assertThat(result.status()).isEqualTo(TicketStatus.ESCALATED);
        assertThat(result.previousStatus()).isEqualTo(TicketStatus.IN_PROGRESS);
        assertThat(result.escalationReasonCode()).isEqualTo(EscalationReasonCode.USER_IMPACT);
        assertThat(result.escalatedBy()).isEqualTo("lead.sam");
        assertThat(result.resolutionCycleId()).isEqualTo(RESOLUTION_CYCLE_ID);
        assertThat(result.version()).isEqualTo(6L);
        assertThat(result.replayed()).isFalse();

        ArgumentCaptor<TicketStatusHistoryEntry> historyCaptor = ArgumentCaptor.forClass(TicketStatusHistoryEntry.class);
        verify(historyWriter).append(historyCaptor.capture());
        assertThat(historyCaptor.getValue().fromStatus()).isEqualTo(TicketStatus.IN_PROGRESS);
        assertThat(historyCaptor.getValue().toStatus()).isEqualTo(TicketStatus.ESCALATED);
        assertThat(historyCaptor.getValue().transitionId()).isEqualTo("SM-043");
        assertThat(historyCaptor.getValue().reasonCode()).isEqualTo("TICKET_ESCALATED");
        assertThat(historyCaptor.getValue().aggregateVersion()).isEqualTo(6L);

        ArgumentCaptor<AuditRecordEntry> auditCaptor = ArgumentCaptor.forClass(AuditRecordEntry.class);
        verify(auditRecordPort).append(auditCaptor.capture());
        assertThat(auditCaptor.getValue().action()).isEqualTo("TICKET_ESCALATED");
        assertThat(auditCaptor.getValue().outcome()).isEqualTo("SUCCESS");

        ArgumentCaptor<OutboxEventEntry> outboxCaptor = ArgumentCaptor.forClass(OutboxEventEntry.class);
        verify(outboxEventRepository).append(outboxCaptor.capture());
        assertThat(outboxCaptor.getValue().eventType()).isEqualTo("ticket.escalated");
        assertThat(outboxCaptor.getValue().routingKey()).isEqualTo("ticket.escalated.v1");

        verify(idempotencyRepository).complete(any(), any());
        verify(telemetry).recordEscalateCommand("success");
    }

    @Test
    void shouldEscalateAnUnassignedNewTicket() {
        when(guardPort.loadGuard(any())).thenReturn(Optional.of(new TicketEscalationGuard(
            TicketId.of(TICKET_ID), TicketDisplayId.of("INC-42"), TicketStatus.NEW, 5L,
            null, null, null, RESOLUTION_CYCLE_ID, null
        )));

        EscalateTicketResult result = service.escalate(command("key-1"));

        assertThat(result.previousStatus()).isEqualTo(TicketStatus.NEW);
        assertThat(result.status()).isEqualTo(TicketStatus.ESCALATED);
    }

    @Test
    void shouldAllowAServiceActorActingAsAFailureHandler() {
        EscalateTicketCommand command = new EscalateTicketCommand(
            TicketId.of(TICKET_ID), EscalationReasonCode.REPEATED_FAILURE, REASON, 5L,
            new ActorContext("SERVICE", "escalation-policy-worker", "escalation-policy-service", Set.of("ticket:escalate")),
            "key-service", "corr-1", "cmd-1", NOW, VALID_STEP_UP_PROOF
        );

        EscalateTicketResult result = service.escalate(command);

        assertThat(result.status()).isEqualTo(TicketStatus.ESCALATED);
        assertThat(result.escalationReasonCode()).isEqualTo(EscalationReasonCode.REPEATED_FAILURE);
    }

    @Test
    void shouldReturnOriginalResponseOnReplayWithoutAnySideEffects() {
        String storedJson = """
            {"ticketId":"%s","previousStatus":"IN_PROGRESS","status":"ESCALATED","escalationReasonCode":"USER_IMPACT",\
            "escalatedBy":"lead.sam","escalatedAt":"2026-08-07T22:00:00Z","resolutionCycleId":"%s","version":6}
            """.formatted(TICKET_ID, RESOLUTION_CYCLE_ID);
        when(idempotencyRepository.reserve(any())).thenReturn(new IdempotencyReservationOutcome.Replayed(200, storedJson));

        EscalateTicketResult result = service.escalate(command("same-key"));

        assertThat(result.replayed()).isTrue();
        assertThat(result.version()).isEqualTo(6L);
        verify(guardPort, never()).loadGuard(any());
        verify(repository, never()).applyEscalation(any());
        verify(historyWriter, never()).append(any());
        verify(auditRecordPort, never()).append(any());
        verify(outboxEventRepository, never()).append(any());
        verify(telemetry).recordEscalateCommand("replay");
    }

    @Test
    void shouldRejectSameIdempotencyKeyWithADifferentPayload() {
        when(idempotencyRepository.reserve(any())).thenReturn(new IdempotencyReservationOutcome.KeyReused());

        assertThatThrownBy(() -> service.escalate(command("same-key"))).isInstanceOf(IdempotencyKeyReusedException.class);
        verify(repository, never()).applyEscalation(any());
    }

    @Test
    void shouldRejectAFreshInProgressDuplicate() {
        when(idempotencyRepository.reserve(any())).thenReturn(new IdempotencyReservationOutcome.RequestInProgress());

        assertThatThrownBy(() -> service.escalate(command("same-key"))).isInstanceOf(RequestInProgressException.class);
        verify(repository, never()).applyEscalation(any());
    }

    @Test
    void shouldRejectAnActorWithoutTheRequiredScope() {
        EscalateTicketCommand command = new EscalateTicketCommand(
            TicketId.of(TICKET_ID), EscalationReasonCode.USER_IMPACT, REASON, 5L,
            new ActorContext("IT_SUPPORT", "lead.sam", "support-console", Set.of()),
            "key-1", "corr-1", "cmd-1", NOW, null
        );

        assertThatThrownBy(() -> service.escalate(command)).isInstanceOf(TicketAuthorizationException.class);
        verify(idempotencyRepository, never()).reserve(any());
    }

    @Test
    void shouldReturn404WhenTheTicketDoesNotExist() {
        when(guardPort.loadGuard(any())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.escalate(command("key-1"))).isInstanceOf(TicketNotFoundException.class);
    }

    @Test
    void shouldRejectAStaleExpectedVersion() {
        when(guardPort.loadGuard(any())).thenReturn(Optional.of(guardInStatus(TicketStatus.IN_PROGRESS, 6L)));

        assertThatThrownBy(() -> service.escalate(command("key-1")))
            .isInstanceOfSatisfying(TicketVersionConflictException.class, ex -> assertThat(ex.currentVersion()).isEqualTo(6L));
        verify(repository, never()).applyEscalation(any());
    }

    @Test
    void shouldRejectATerminalStatus() {
        when(guardPort.loadGuard(any())).thenReturn(Optional.of(guardInStatus(TicketStatus.CLOSED, 5L)));

        assertThatThrownBy(() -> service.escalate(command("key-1"))).isInstanceOf(InvalidTicketStateException.class);
        verify(repository, never()).applyEscalation(any());
    }

    @Test
    void shouldRejectAnAlreadyEscalatedTicket() {
        when(guardPort.loadGuard(any())).thenReturn(Optional.of(guardInStatus(TicketStatus.ESCALATED, 5L)));

        assertThatThrownBy(() -> service.escalate(command("key-1"))).isInstanceOf(InvalidTicketStateException.class);
        verify(repository, never()).applyEscalation(any());
    }

    @Test
    void shouldRejectAnInvalidStateDetectedAtTheRepositoryLayer() {
        doReturn(new TicketEscalationUpdateOutcome.InvalidState(TicketStatus.CLOSED)).when(repository).applyEscalation(any());

        assertThatThrownBy(() -> service.escalate(command("key-1"))).isInstanceOf(InvalidTicketStateException.class);
    }

    @Test
    void shouldRejectATicketMissingRaceDetectedAtTheRepositoryLayer() {
        doReturn(new TicketEscalationUpdateOutcome.TicketMissing()).when(repository).applyEscalation(any());

        assertThatThrownBy(() -> service.escalate(command("key-1"))).isInstanceOf(TicketNotFoundException.class);
    }

    @Test
    void shouldRejectAVersionMismatchRaceDetectedAtTheRepositoryLayer() {
        doReturn(new TicketEscalationUpdateOutcome.VersionMismatch(99L)).when(repository).applyEscalation(any());

        assertThatThrownBy(() -> service.escalate(command("key-1")))
            .isInstanceOfSatisfying(TicketVersionConflictException.class, ex -> assertThat(ex.currentVersion()).isEqualTo(99L));
    }

    /** SPEC-TW-036: Escalate is a phase-09 high-risk command — a missing step-up proof is rejected before the persistence mutation. */
    @Test
    void shouldRejectEscalateWithoutAStepUpProof() {
        EscalateTicketCommand command = new EscalateTicketCommand(
            TicketId.of(TICKET_ID), EscalationReasonCode.USER_IMPACT, REASON, 5L,
            new ActorContext("IT_SUPPORT", "lead.sam", "support-console", Set.of("ticket:escalate")),
            "key-1", "corr-1", "cmd-1", NOW, null
        );

        assertThatThrownBy(() -> service.escalate(command)).isInstanceOf(StepUpAuthenticationRequiredException.class);
        verify(repository, never()).applyEscalation(any());
    }

    @Test
    void shouldRejectEscalateWithAnExpiredStepUpProof() {
        StepUpProof expiredProof = new StepUpProof("proof-1", "MFA_TOTP", NOW.minusSeconds(7200), NOW.minusSeconds(3600));
        EscalateTicketCommand command = new EscalateTicketCommand(
            TicketId.of(TICKET_ID), EscalationReasonCode.USER_IMPACT, REASON, 5L,
            new ActorContext("IT_SUPPORT", "lead.sam", "support-console", Set.of("ticket:escalate")),
            "key-1", "corr-1", "cmd-1", NOW, expiredProof
        );

        assertThatThrownBy(() -> service.escalate(command)).isInstanceOf(StepUpAuthenticationRequiredException.class);
        verify(repository, never()).applyEscalation(any());
    }

    @Test
    void shouldAllowEscalateWithAValidStepUpProof() {
        EscalateTicketResult result = service.escalate(command("key-1"));

        assertThat(result.status()).isEqualTo(TicketStatus.ESCALATED);
        verify(stepUpAuditRecorder).recordAllowed(any(), any(), any(), any(), any(), any());
    }
}
