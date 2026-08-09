package dev.opsmind.ticketworkflow.ticket.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.opsmind.ticketworkflow.ticket.application.command.ActorContext;
import dev.opsmind.ticketworkflow.ticket.application.command.ResumeEscalatedTicketCommand;
import dev.opsmind.ticketworkflow.ticket.application.command.ResumeEscalatedTicketResult;
import dev.opsmind.ticketworkflow.ticket.application.event.TicketEscalationResumedEventMapper;
import dev.opsmind.ticketworkflow.ticket.application.exception.IdempotencyKeyReusedException;
import dev.opsmind.ticketworkflow.ticket.application.exception.RequestInProgressException;
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
import dev.opsmind.ticketworkflow.ticket.application.port.out.SupportAgentDirectoryPort;
import dev.opsmind.ticketworkflow.ticket.application.port.out.SupportAgentRecord;
import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketEscalationResumeGuard;
import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketEscalationResumeGuardPort;
import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketEscalationResumeRepository;
import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketEscalationResumeUpdate;
import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketEscalationResumeUpdateOutcome;
import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketHistoryWriter;
import dev.opsmind.ticketworkflow.ticket.application.service.ResumeEscalatedTicketApplicationService;
import dev.opsmind.ticketworkflow.ticket.domain.exception.InvalidTicketTransitionException;
import dev.opsmind.ticketworkflow.ticket.domain.value.EscalationResumeReasonCode;
import dev.opsmind.ticketworkflow.ticket.domain.value.OwnershipStatus;
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

/** SPEC-TW-032: the full success transaction, guard rejections, and idempotency outcomes. Mirrors {@code EscalateTicketApplicationServiceTest}'s (SPEC-TW-031) shape. */
@Tag("unit")
class ResumeEscalatedTicketApplicationServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-08T22:00:00Z");
    private static final UUID TICKET_ID = UUID.fromString("6c2ad02e-c394-41fb-8e38-dfffd581a59d");
    private static final UUID RESOLUTION_CYCLE_ID = UUID.fromString("4bde946d-60b8-4e4e-9970-6a0d0d1448f1");
    private static final UUID SUPPORT_QUEUE_ID = UUID.fromString("9d38b723-4a4d-47d3-94fe-32ef78cc0690");
    private static final String TEAM_ID = "TEAM-A";
    private static final String ASSIGNEE_ID = "sam.support";
    private static final String REASON = "Root cause identified and mitigated; resuming active work.";

    private TicketEscalationResumeGuardPort guardPort;
    private SupportAgentDirectoryPort agentDirectoryPort;
    private TicketEscalationResumeRepository repository;
    private TicketHistoryWriter historyWriter;
    private AuditRecordPort auditRecordPort;
    private OutboxEventRepository outboxEventRepository;
    private IdempotencyRepository idempotencyRepository;
    private ClockPort clock;
    private TicketTelemetry telemetry;
    private ResumeEscalatedTicketApplicationService service;

    @BeforeEach
    void setUp() {
        guardPort = mock(TicketEscalationResumeGuardPort.class);
        agentDirectoryPort = mock(SupportAgentDirectoryPort.class);
        repository = mock(TicketEscalationResumeRepository.class);
        historyWriter = mock(TicketHistoryWriter.class);
        auditRecordPort = mock(AuditRecordPort.class);
        outboxEventRepository = mock(OutboxEventRepository.class);
        idempotencyRepository = mock(IdempotencyRepository.class);
        clock = mock(ClockPort.class);
        telemetry = mock(TicketTelemetry.class);

        when(clock.now()).thenReturn(NOW);
        when(idempotencyRepository.reserve(any())).thenReturn(new IdempotencyReservationOutcome.Reserved(UUID.randomUUID()));
        when(guardPort.loadGuard(any())).thenReturn(Optional.of(defaultGuard()));
        when(agentDirectoryPort.findById(ASSIGNEE_ID)).thenReturn(Optional.of(new SupportAgentRecord(ASSIGNEE_ID, "Sam Lee", "IT_SUPPORT", true)));
        when(repository.applyResume(any())).thenAnswer(invocation -> {
            TicketEscalationResumeUpdate update = invocation.getArgument(0);
            return new TicketEscalationResumeUpdateOutcome.Updated(update.expectedVersion() + 1);
        });

        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        service = new ResumeEscalatedTicketApplicationService(
            guardPort, agentDirectoryPort, repository, historyWriter, auditRecordPort, outboxEventRepository, idempotencyRepository,
            clock, new RequestHashCalculator(objectMapper), new TicketEscalationResumedEventMapper(), telemetry, objectMapper
        );
    }

    private TicketEscalationResumeGuard defaultGuard() {
        return guardInStatus(TicketStatus.ESCALATED, 5L);
    }

    private TicketEscalationResumeGuard guardInStatus(TicketStatus status, long version) {
        return new TicketEscalationResumeGuard(
            TicketId.of(TICKET_ID), TicketDisplayId.of("INC-42"), status, version,
            TEAM_ID, SupportQueueId.of(SUPPORT_QUEUE_ID), ASSIGNEE_ID, RESOLUTION_CYCLE_ID
        );
    }

    private ResumeEscalatedTicketCommand command(String idempotencyKey) {
        return new ResumeEscalatedTicketCommand(
            TicketId.of(TICKET_ID), EscalationResumeReasonCode.ROOT_CAUSE_RESOLVED, REASON, 5L,
            new ActorContext("IT_SUPPORT", "lead.sam", "support-console", Set.of("ticket:escalation-resume")),
            idempotencyKey, "corr-1", "cmd-1", NOW
        );
    }

    @Test
    void shouldResumeSuccessfullyAndPersistHistoryAuditOutboxAndIdempotency() {
        ResumeEscalatedTicketResult result = service.resume(command("key-1"));

        assertThat(result.status()).isEqualTo(TicketStatus.IN_PROGRESS);
        assertThat(result.previousStatus()).isEqualTo(TicketStatus.ESCALATED);
        assertThat(result.resumeReasonCode()).isEqualTo(EscalationResumeReasonCode.ROOT_CAUSE_RESOLVED);
        assertThat(result.resumedBy()).isEqualTo("lead.sam");
        assertThat(result.resolutionCycleId()).isEqualTo(RESOLUTION_CYCLE_ID);
        assertThat(result.ownershipStatus()).isEqualTo(OwnershipStatus.ACTIVE);
        assertThat(result.version()).isEqualTo(6L);
        assertThat(result.replayed()).isFalse();

        ArgumentCaptor<TicketStatusHistoryEntry> historyCaptor = ArgumentCaptor.forClass(TicketStatusHistoryEntry.class);
        verify(historyWriter).append(historyCaptor.capture());
        assertThat(historyCaptor.getValue().fromStatus()).isEqualTo(TicketStatus.ESCALATED);
        assertThat(historyCaptor.getValue().toStatus()).isEqualTo(TicketStatus.IN_PROGRESS);
        assertThat(historyCaptor.getValue().transitionId()).isEqualTo("SM-049");
        assertThat(historyCaptor.getValue().reasonCode()).isEqualTo("TICKET_ESCALATION_RESUMED");
        assertThat(historyCaptor.getValue().aggregateVersion()).isEqualTo(6L);

        ArgumentCaptor<AuditRecordEntry> auditCaptor = ArgumentCaptor.forClass(AuditRecordEntry.class);
        verify(auditRecordPort).append(auditCaptor.capture());
        assertThat(auditCaptor.getValue().action()).isEqualTo("TICKET_ESCALATION_RESUMED");
        assertThat(auditCaptor.getValue().outcome()).isEqualTo("SUCCESS");

        ArgumentCaptor<OutboxEventEntry> outboxCaptor = ArgumentCaptor.forClass(OutboxEventEntry.class);
        verify(outboxEventRepository).append(outboxCaptor.capture());
        assertThat(outboxCaptor.getValue().eventType()).isEqualTo("ticket.escalation-resumed");
        assertThat(outboxCaptor.getValue().routingKey()).isEqualTo("ticket.escalation-resumed.v1");

        verify(idempotencyRepository).complete(any(), any());
        verify(telemetry).recordEscalationResumeCommand("success");
    }

    @Test
    void shouldReportUnassignedOwnershipForAnUnownedTicket() {
        when(guardPort.loadGuard(any())).thenReturn(Optional.of(new TicketEscalationResumeGuard(
            TicketId.of(TICKET_ID), TicketDisplayId.of("INC-42"), TicketStatus.ESCALATED, 5L,
            TEAM_ID, SupportQueueId.of(SUPPORT_QUEUE_ID), null, RESOLUTION_CYCLE_ID
        )));

        ResumeEscalatedTicketResult result = service.resume(command("key-1"));

        assertThat(result.ownershipStatus()).isEqualTo(OwnershipStatus.UNASSIGNED);
        verify(agentDirectoryPort, never()).findById(any());
    }

    @Test
    void shouldReportAnInactiveAssigneeWithoutBlockingTheResume() {
        when(agentDirectoryPort.findById(ASSIGNEE_ID)).thenReturn(Optional.of(new SupportAgentRecord(ASSIGNEE_ID, "Sam Lee", "IT_SUPPORT", false)));

        ResumeEscalatedTicketResult result = service.resume(command("key-1"));

        assertThat(result.status()).isEqualTo(TicketStatus.IN_PROGRESS);
        assertThat(result.ownershipStatus()).isEqualTo(OwnershipStatus.ASSIGNEE_INACTIVE);
    }

    @Test
    void shouldReturnOriginalResponseOnReplayWithoutAnySideEffects() {
        String storedJson = """
            {"ticketId":"%s","previousStatus":"ESCALATED","status":"IN_PROGRESS","resumeReasonCode":"ROOT_CAUSE_RESOLVED",\
            "resumedBy":"lead.sam","resumedAt":"2026-08-08T22:00:00Z","resolutionCycleId":"%s","ownershipStatus":"ACTIVE","version":6}
            """.formatted(TICKET_ID, RESOLUTION_CYCLE_ID);
        when(idempotencyRepository.reserve(any())).thenReturn(new IdempotencyReservationOutcome.Replayed(200, storedJson));

        ResumeEscalatedTicketResult result = service.resume(command("same-key"));

        assertThat(result.replayed()).isTrue();
        assertThat(result.version()).isEqualTo(6L);
        verify(guardPort, never()).loadGuard(any());
        verify(repository, never()).applyResume(any());
        verify(historyWriter, never()).append(any());
        verify(auditRecordPort, never()).append(any());
        verify(outboxEventRepository, never()).append(any());
        verify(telemetry).recordEscalationResumeCommand("replay");
    }

    @Test
    void shouldRejectSameIdempotencyKeyWithADifferentPayload() {
        when(idempotencyRepository.reserve(any())).thenReturn(new IdempotencyReservationOutcome.KeyReused());

        assertThatThrownBy(() -> service.resume(command("same-key"))).isInstanceOf(IdempotencyKeyReusedException.class);
        verify(repository, never()).applyResume(any());
    }

    @Test
    void shouldRejectAFreshInProgressDuplicate() {
        when(idempotencyRepository.reserve(any())).thenReturn(new IdempotencyReservationOutcome.RequestInProgress());

        assertThatThrownBy(() -> service.resume(command("same-key"))).isInstanceOf(RequestInProgressException.class);
        verify(repository, never()).applyResume(any());
    }

    @Test
    void shouldRejectAnActorWithoutTheRequiredScope() {
        ResumeEscalatedTicketCommand command = new ResumeEscalatedTicketCommand(
            TicketId.of(TICKET_ID), EscalationResumeReasonCode.ROOT_CAUSE_RESOLVED, REASON, 5L,
            new ActorContext("IT_SUPPORT", "lead.sam", "support-console", Set.of()),
            "key-1", "corr-1", "cmd-1", NOW
        );

        assertThatThrownBy(() -> service.resume(command)).isInstanceOf(TicketAuthorizationException.class);
        verify(idempotencyRepository, never()).reserve(any());
    }

    @Test
    void shouldReturn404WhenTheTicketDoesNotExist() {
        when(guardPort.loadGuard(any())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.resume(command("key-1"))).isInstanceOf(TicketNotFoundException.class);
    }

    @Test
    void shouldRejectAStaleExpectedVersion() {
        when(guardPort.loadGuard(any())).thenReturn(Optional.of(guardInStatus(TicketStatus.ESCALATED, 6L)));

        assertThatThrownBy(() -> service.resume(command("key-1")))
            .isInstanceOfSatisfying(TicketVersionConflictException.class, ex -> assertThat(ex.currentVersion()).isEqualTo(6L));
        verify(repository, never()).applyResume(any());
    }

    @Test
    void shouldRejectAStatusOtherThanEscalated() {
        when(guardPort.loadGuard(any())).thenReturn(Optional.of(guardInStatus(TicketStatus.IN_PROGRESS, 5L)));

        assertThatThrownBy(() -> service.resume(command("key-1"))).isInstanceOf(InvalidTicketTransitionException.class);
        verify(repository, never()).applyResume(any());
    }

    @Test
    void shouldRejectATerminalStatus() {
        when(guardPort.loadGuard(any())).thenReturn(Optional.of(guardInStatus(TicketStatus.CLOSED, 5L)));

        assertThatThrownBy(() -> service.resume(command("key-1"))).isInstanceOf(InvalidTicketTransitionException.class);
        verify(repository, never()).applyResume(any());
    }

    @Test
    void shouldRejectAnInvalidStateDetectedAtTheRepositoryLayer() {
        doReturn(new TicketEscalationResumeUpdateOutcome.InvalidState(TicketStatus.IN_PROGRESS)).when(repository).applyResume(any());

        assertThatThrownBy(() -> service.resume(command("key-1"))).isInstanceOf(InvalidTicketTransitionException.class);
    }

    @Test
    void shouldRejectATicketMissingRaceDetectedAtTheRepositoryLayer() {
        doReturn(new TicketEscalationResumeUpdateOutcome.TicketMissing()).when(repository).applyResume(any());

        assertThatThrownBy(() -> service.resume(command("key-1"))).isInstanceOf(TicketNotFoundException.class);
    }

    @Test
    void shouldRejectAVersionMismatchRaceDetectedAtTheRepositoryLayer() {
        doReturn(new TicketEscalationResumeUpdateOutcome.VersionMismatch(99L)).when(repository).applyResume(any());

        assertThatThrownBy(() -> service.resume(command("key-1")))
            .isInstanceOfSatisfying(TicketVersionConflictException.class, ex -> assertThat(ex.currentVersion()).isEqualTo(99L));
    }
}
