package dev.opsmind.ticketworkflow.ticket.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.opsmind.ticketworkflow.configuration.TicketWorkflowProperties;
import dev.opsmind.ticketworkflow.ticket.application.command.ActorContext;
import dev.opsmind.ticketworkflow.ticket.application.command.ResolveTicketCommand;
import dev.opsmind.ticketworkflow.ticket.application.command.ResolveTicketResult;
import dev.opsmind.ticketworkflow.ticket.application.event.TicketResolvedEventMapper;
import dev.opsmind.ticketworkflow.ticket.application.exception.IdempotencyKeyReusedException;
import dev.opsmind.ticketworkflow.ticket.application.exception.QueueAccessDeniedException;
import dev.opsmind.ticketworkflow.ticket.application.exception.RequestInProgressException;
import dev.opsmind.ticketworkflow.ticket.application.exception.ResolutionCycleAlreadyCompletedException;
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
import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketResolveGuard;
import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketResolveGuardPort;
import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketResolveRepository;
import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketResolveUpdate;
import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketResolveUpdateOutcome;
import dev.opsmind.ticketworkflow.ticket.application.service.ResolveTicketApplicationService;
import dev.opsmind.ticketworkflow.ticket.domain.exception.InvalidStatusTransitionException;
import dev.opsmind.ticketworkflow.ticket.domain.exception.TicketNotAssignedException;
import dev.opsmind.ticketworkflow.ticket.domain.value.ResolutionCode;
import dev.opsmind.ticketworkflow.ticket.domain.value.ResolutionCycleStatus;
import dev.opsmind.ticketworkflow.ticket.domain.value.SupportQueueId;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketDisplayId;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketId;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Duration;
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

/** SPEC-TW-010: the full success transaction, guard rejections, and idempotency outcomes. */
@Tag("unit")
class ResolveTicketApplicationServiceTest {

    private static final Instant NOW = Instant.parse("2026-07-31T19:05:00Z");
    private static final UUID TICKET_ID = UUID.fromString("6c2ad02e-c394-41fb-8e38-dfffd581a59d");
    private static final UUID RESOLUTION_CYCLE_ID = UUID.fromString("4bde946d-60b8-4e4e-9970-6a0d0d1448f1");
    private static final UUID SUPPORT_QUEUE_ID = UUID.fromString("9d38b723-4a4d-47d3-94fe-32ef78cc0690");
    private static final String TEAM_ID = "team-endpoint";
    private static final String ASSIGNEE_ID = "sam.support";
    private static final String SUMMARY = "Reinstalled the endpoint management profile and confirmed the device checked in.";

    private TicketResolveGuardPort guardPort;
    private TicketResolveRepository resolveRepository;
    private TicketHistoryWriter historyWriter;
    private AuditRecordPort auditRecordPort;
    private OutboxEventRepository outboxEventRepository;
    private IdempotencyRepository idempotencyRepository;
    private ClockPort clock;
    private TicketTelemetry telemetry;
    private ResolveTicketApplicationService service;

    @BeforeEach
    void setUp() {
        guardPort = mock(TicketResolveGuardPort.class);
        resolveRepository = mock(TicketResolveRepository.class);
        historyWriter = mock(TicketHistoryWriter.class);
        auditRecordPort = mock(AuditRecordPort.class);
        outboxEventRepository = mock(OutboxEventRepository.class);
        idempotencyRepository = mock(IdempotencyRepository.class);
        clock = mock(ClockPort.class);
        telemetry = mock(TicketTelemetry.class);

        when(clock.now()).thenReturn(NOW);
        when(idempotencyRepository.reserve(any())).thenReturn(new IdempotencyReservationOutcome.Reserved(UUID.randomUUID()));
        when(guardPort.loadGuard(any())).thenReturn(Optional.of(defaultGuard()));
        when(resolveRepository.applyResolution(any())).thenAnswer(invocation -> {
            TicketResolveUpdate update = invocation.getArgument(0);
            return new TicketResolveUpdateOutcome.Updated(update.expectedVersion() + 1);
        });

        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        TicketWorkflowProperties properties = new TicketWorkflowProperties(
            null, null,
            new TicketWorkflowProperties.Sla("DEFAULT", Duration.ofHours(4), Duration.ofHours(24), Duration.ofHours(4), Duration.ofDays(7))
        );
        service = new ResolveTicketApplicationService(
            guardPort, resolveRepository, historyWriter, auditRecordPort, outboxEventRepository, idempotencyRepository,
            clock, new RequestHashCalculator(objectMapper), new TicketResolvedEventMapper(), telemetry, objectMapper, properties
        );
    }

    private TicketResolveGuard defaultGuard() {
        return new TicketResolveGuard(
            TicketId.of(TICKET_ID), TicketDisplayId.of("INC-42"), TicketStatus.IN_PROGRESS, 17L,
            SupportQueueId.of(SUPPORT_QUEUE_ID), TEAM_ID, ASSIGNEE_ID, RESOLUTION_CYCLE_ID, ResolutionCycleStatus.ACTIVE
        );
    }

    private ResolveTicketCommand command(String idempotencyKey) {
        return new ResolveTicketCommand(
            TicketId.of(TICKET_ID), ResolutionCode.FIXED, SUMMARY, 17L,
            new ActorContext("IT_SUPPORT", "sam.support", "support-console", Set.of("ticket:resolve")),
            Set.of(TEAM_ID), idempotencyKey, "corr-1", "cmd-1", NOW
        );
    }

    @Test
    void shouldResolveSuccessfullyAndPersistHistoryAuditOutboxAndIdempotency() {
        ResolveTicketResult result = service.resolve(command("key-1"));

        assertThat(result.status()).isEqualTo(TicketStatus.RESOLVED);
        assertThat(result.previousStatus()).isEqualTo(TicketStatus.IN_PROGRESS);
        assertThat(result.resolutionCode()).isEqualTo(ResolutionCode.FIXED);
        assertThat(result.resolutionSummary()).isEqualTo(SUMMARY);
        assertThat(result.resolvedBy()).isEqualTo("sam.support");
        assertThat(result.resolutionCycleId()).isEqualTo(RESOLUTION_CYCLE_ID);
        assertThat(result.autoCloseDueAt()).isEqualTo(NOW.plus(Duration.ofDays(7)));
        assertThat(result.version()).isEqualTo(18L);
        assertThat(result.replayed()).isFalse();

        ArgumentCaptor<TicketStatusHistoryEntry> historyCaptor = ArgumentCaptor.forClass(TicketStatusHistoryEntry.class);
        verify(historyWriter).append(historyCaptor.capture());
        assertThat(historyCaptor.getValue().fromStatus()).isEqualTo(TicketStatus.IN_PROGRESS);
        assertThat(historyCaptor.getValue().toStatus()).isEqualTo(TicketStatus.RESOLVED);
        assertThat(historyCaptor.getValue().transitionId()).isEqualTo("SM-010");
        assertThat(historyCaptor.getValue().reasonCode()).isEqualTo("TICKET_RESOLVED");
        assertThat(historyCaptor.getValue().aggregateVersion()).isEqualTo(18L);

        ArgumentCaptor<AuditRecordEntry> auditCaptor = ArgumentCaptor.forClass(AuditRecordEntry.class);
        verify(auditRecordPort).append(auditCaptor.capture());
        assertThat(auditCaptor.getValue().action()).isEqualTo("TICKET_RESOLVED");
        assertThat(auditCaptor.getValue().outcome()).isEqualTo("SUCCESS");
        assertThat(auditCaptor.getValue().ticketStatusBefore()).isEqualTo("IN_PROGRESS");
        assertThat(auditCaptor.getValue().ticketStatusAfter()).isEqualTo("RESOLVED");

        ArgumentCaptor<OutboxEventEntry> outboxCaptor = ArgumentCaptor.forClass(OutboxEventEntry.class);
        verify(outboxEventRepository).append(outboxCaptor.capture());
        assertThat(outboxCaptor.getValue().eventType()).isEqualTo("ticket.resolved");
        assertThat(outboxCaptor.getValue().routingKey()).isEqualTo("ticket.resolved.v1");
        assertThat(outboxCaptor.getValue().payload().get("resolutionCycleId")).isEqualTo(RESOLUTION_CYCLE_ID.toString());

        verify(idempotencyRepository).complete(any(), any());
        verify(telemetry).recordResolutionCommand("FIXED", "success");
    }

    @Test
    void shouldReturnOriginalResponseOnReplayWithoutAnySideEffects() {
        String storedJson = """
            {"ticketId":"%s","previousStatus":"IN_PROGRESS","status":"RESOLVED","resolutionCode":"FIXED",\
            "resolutionSummary":"%s","resolvedBy":"sam.support","resolvedAt":"2026-07-31T19:05:00Z",\
            "resolutionCycleId":"%s","autoCloseDueAt":"2026-08-07T19:05:00Z","version":18}
            """.formatted(TICKET_ID, SUMMARY, RESOLUTION_CYCLE_ID);
        when(idempotencyRepository.reserve(any())).thenReturn(new IdempotencyReservationOutcome.Replayed(200, storedJson));

        ResolveTicketResult result = service.resolve(command("same-key"));

        assertThat(result.replayed()).isTrue();
        assertThat(result.version()).isEqualTo(18L);
        verify(guardPort, never()).loadGuard(any());
        verify(resolveRepository, never()).applyResolution(any());
        verify(historyWriter, never()).append(any());
        verify(auditRecordPort, never()).append(any());
        verify(outboxEventRepository, never()).append(any());
        verify(telemetry).recordResolutionCommand("FIXED", "replay");
    }

    @Test
    void shouldRejectSameIdempotencyKeyWithADifferentPayload() {
        when(idempotencyRepository.reserve(any())).thenReturn(new IdempotencyReservationOutcome.KeyReused());

        assertThatThrownBy(() -> service.resolve(command("same-key"))).isInstanceOf(IdempotencyKeyReusedException.class);
        verify(resolveRepository, never()).applyResolution(any());
    }

    @Test
    void shouldRejectAFreshInProgressDuplicate() {
        when(idempotencyRepository.reserve(any())).thenReturn(new IdempotencyReservationOutcome.RequestInProgress());

        assertThatThrownBy(() -> service.resolve(command("same-key"))).isInstanceOf(RequestInProgressException.class);
        verify(resolveRepository, never()).applyResolution(any());
    }

    @Test
    void shouldRejectAnActorWithoutTheRequiredScope() {
        ResolveTicketCommand command = new ResolveTicketCommand(
            TicketId.of(TICKET_ID), ResolutionCode.FIXED, SUMMARY, 17L,
            new ActorContext("IT_SUPPORT", "sam.support", "support-console", Set.of()),
            Set.of(TEAM_ID), "key-1", "corr-1", "cmd-1", NOW
        );

        assertThatThrownBy(() -> service.resolve(command)).isInstanceOf(TicketAuthorizationException.class);
        verify(idempotencyRepository, never()).reserve(any());
    }

    @Test
    void shouldRejectATicketOutsideTheActorsAuthorizedQueue() {
        ResolveTicketCommand command = new ResolveTicketCommand(
            TicketId.of(TICKET_ID), ResolutionCode.FIXED, SUMMARY, 17L,
            new ActorContext("IT_SUPPORT", "sam.support", "support-console", Set.of("ticket:resolve")),
            Set.of("some-other-team"), "key-1", "corr-1", "cmd-1", NOW
        );

        assertThatThrownBy(() -> service.resolve(command)).isInstanceOf(QueueAccessDeniedException.class);
        verify(resolveRepository, never()).applyResolution(any());
    }

    @Test
    void shouldReturn404WhenTheTicketDoesNotExist() {
        when(guardPort.loadGuard(any())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.resolve(command("key-1"))).isInstanceOf(TicketNotFoundException.class);
    }

    @Test
    void shouldRejectAStaleExpectedVersion() {
        when(guardPort.loadGuard(any())).thenReturn(Optional.of(new TicketResolveGuard(
            TicketId.of(TICKET_ID), TicketDisplayId.of("INC-42"), TicketStatus.IN_PROGRESS, 18L,
            SupportQueueId.of(SUPPORT_QUEUE_ID), TEAM_ID, ASSIGNEE_ID, RESOLUTION_CYCLE_ID, ResolutionCycleStatus.ACTIVE
        )));

        assertThatThrownBy(() -> service.resolve(command("key-1")))
            .isInstanceOfSatisfying(TicketVersionConflictException.class, ex -> assertThat(ex.currentVersion()).isEqualTo(18L));
        verify(resolveRepository, never()).applyResolution(any());
    }

    @Test
    void shouldRejectATicketWithoutACurrentResolutionCycle() {
        when(guardPort.loadGuard(any())).thenReturn(Optional.of(new TicketResolveGuard(
            TicketId.of(TICKET_ID), TicketDisplayId.of("INC-42"), TicketStatus.IN_PROGRESS, 17L,
            SupportQueueId.of(SUPPORT_QUEUE_ID), TEAM_ID, ASSIGNEE_ID, null, null
        )));

        assertThatThrownBy(() -> service.resolve(command("key-1"))).isInstanceOf(ResolutionCycleNotFoundException.class);
        verify(resolveRepository, never()).applyResolution(any());
    }

    @Test
    void shouldRejectAnAlreadyCompletedResolutionCycle() {
        when(guardPort.loadGuard(any())).thenReturn(Optional.of(new TicketResolveGuard(
            TicketId.of(TICKET_ID), TicketDisplayId.of("INC-42"), TicketStatus.IN_PROGRESS, 17L,
            SupportQueueId.of(SUPPORT_QUEUE_ID), TEAM_ID, ASSIGNEE_ID, RESOLUTION_CYCLE_ID, ResolutionCycleStatus.RESOLVED
        )));

        assertThatThrownBy(() -> service.resolve(command("key-1"))).isInstanceOf(ResolutionCycleAlreadyCompletedException.class);
        verify(resolveRepository, never()).applyResolution(any());
    }

    @Test
    void shouldRejectAnUnassignedTicketDetectedAtTheRepositoryLayer() {
        org.mockito.Mockito.doReturn(new TicketResolveUpdateOutcome.NotAssigned()).when(resolveRepository).applyResolution(any());

        assertThatThrownBy(() -> service.resolve(command("key-1"))).isInstanceOf(TicketNotAssignedException.class);
    }

    @Test
    void shouldRejectAnInvalidStateDetectedAtTheRepositoryLayer() {
        org.mockito.Mockito.doReturn(new TicketResolveUpdateOutcome.InvalidState(TicketStatus.ASSIGNED)).when(resolveRepository).applyResolution(any());

        assertThatThrownBy(() -> service.resolve(command("key-1"))).isInstanceOf(InvalidStatusTransitionException.class);
    }

    @Test
    void shouldRejectAResolutionCycleRaceDetectedAtTheRepositoryLayer() {
        org.mockito.Mockito.doReturn(new TicketResolveUpdateOutcome.ResolutionCycleConflict()).when(resolveRepository).applyResolution(any());

        assertThatThrownBy(() -> service.resolve(command("key-1"))).isInstanceOf(ResolutionCycleAlreadyCompletedException.class);
    }
}
