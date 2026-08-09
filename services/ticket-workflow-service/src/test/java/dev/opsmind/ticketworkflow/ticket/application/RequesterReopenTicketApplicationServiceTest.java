package dev.opsmind.ticketworkflow.ticket.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.opsmind.ticketworkflow.ticket.application.command.ActorContext;
import dev.opsmind.ticketworkflow.ticket.application.command.ReopenTicketResult;
import dev.opsmind.ticketworkflow.ticket.application.command.RequesterReopenTicketCommand;
import dev.opsmind.ticketworkflow.ticket.application.event.TicketReopenedEventMapper;
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
import dev.opsmind.ticketworkflow.ticket.application.port.out.SupportAgentDirectoryPort;
import dev.opsmind.ticketworkflow.ticket.application.port.out.SupportAgentRecord;
import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketHistoryWriter;
import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketReopenRepository;
import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketReopenUpdate;
import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketReopenUpdateOutcome;
import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketRequesterReopenGuard;
import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketRequesterReopenGuardPort;
import dev.opsmind.ticketworkflow.ticket.application.service.RequesterReopenTicketApplicationService;
import dev.opsmind.ticketworkflow.ticket.domain.exception.InvalidTicketStateException;
import dev.opsmind.ticketworkflow.ticket.domain.value.OwnershipStatus;
import dev.opsmind.ticketworkflow.ticket.domain.value.ReopenReasonCode;
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

/** SPEC-TW-028: the full success transaction, ownership resolution, guard rejections, and idempotency outcomes. Mirrors {@code ReopenTicketApplicationServiceTest}'s (SPEC-TW-011) shape. */
@Tag("unit")
class RequesterReopenTicketApplicationServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-07T21:30:00Z");
    private static final UUID TICKET_ID = UUID.fromString("6c2ad02e-c394-41fb-8e38-dfffd581a59d");
    private static final UUID RESOLUTION_CYCLE_ID = UUID.fromString("4bde946d-60b8-4e4e-9970-6a0d0d1448f1");
    private static final UUID SUPPORT_QUEUE_ID = UUID.fromString("9d38b723-4a4d-47d3-94fe-32ef78cc0690");
    private static final String REQUESTER_ID = "employee-123";
    private static final String ASSIGNEE_ID = "sam.support";
    private static final String REASON = "The requester reported the same endpoint enrollment failure returned after reboot.";

    private TicketRequesterReopenGuardPort guardPort;
    private TicketReopenRepository reopenRepository;
    private SupportAgentDirectoryPort agentDirectoryPort;
    private TicketHistoryWriter historyWriter;
    private AuditRecordPort auditRecordPort;
    private OutboxEventRepository outboxEventRepository;
    private IdempotencyRepository idempotencyRepository;
    private ClockPort clock;
    private TicketTelemetry telemetry;
    private RequesterReopenTicketApplicationService service;

    @BeforeEach
    void setUp() {
        guardPort = mock(TicketRequesterReopenGuardPort.class);
        reopenRepository = mock(TicketReopenRepository.class);
        agentDirectoryPort = mock(SupportAgentDirectoryPort.class);
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
        when(reopenRepository.applyReopen(any())).thenAnswer(invocation -> {
            TicketReopenUpdate update = invocation.getArgument(0);
            return new TicketReopenUpdateOutcome.Updated(update.expectedVersion() + 1);
        });

        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        service = new RequesterReopenTicketApplicationService(
            guardPort, reopenRepository, agentDirectoryPort, historyWriter, auditRecordPort, outboxEventRepository,
            idempotencyRepository, clock, new RequestHashCalculator(objectMapper), new TicketReopenedEventMapper(), telemetry, objectMapper
        );
    }

    private TicketRequesterReopenGuard defaultGuard() {
        return guardInStatus(TicketStatus.RESOLVED, 19L);
    }

    private TicketRequesterReopenGuard guardInStatus(TicketStatus status, long version) {
        ResolutionCycleStatus cycleStatus = status == TicketStatus.CLOSED ? ResolutionCycleStatus.CLOSED : ResolutionCycleStatus.RESOLVED;
        return new TicketRequesterReopenGuard(
            TicketId.of(TICKET_ID), TicketDisplayId.of("INC-42"), REQUESTER_ID, status, version,
            SupportQueueId.of(SUPPORT_QUEUE_ID), ASSIGNEE_ID, RESOLUTION_CYCLE_ID, cycleStatus, 1, 0
        );
    }

    /** The primary caller: the ticket's own requester. */
    private RequesterReopenTicketCommand employeeCommand(String idempotencyKey) {
        return new RequesterReopenTicketCommand(
            TicketId.of(TICKET_ID), ReopenReasonCode.REQUESTER_REPORTED_NOT_FIXED, REASON, 19L,
            new ActorContext("EMPLOYEE", REQUESTER_ID, "employee-portal", Set.of("ticket:reopen-request")),
            idempotencyKey, "corr-1", "cmd-1", NOW
        );
    }

    @Test
    void shouldReopenSuccessfullyAndPersistHistoryAuditOutboxAndIdempotency() {
        ReopenTicketResult result = service.reopen(employeeCommand("key-1"));

        assertThat(result.status()).isEqualTo(TicketStatus.IN_PROGRESS);
        assertThat(result.previousStatus()).isEqualTo(TicketStatus.RESOLVED);
        assertThat(result.previousResolutionCycleId()).isEqualTo(RESOLUTION_CYCLE_ID);
        assertThat(result.newResolutionCycleId()).isNotEqualTo(RESOLUTION_CYCLE_ID);
        assertThat(result.reopenReasonCode()).isEqualTo(ReopenReasonCode.REQUESTER_REPORTED_NOT_FIXED);
        assertThat(result.reopenedBy()).isEqualTo(REQUESTER_ID);
        assertThat(result.reopenCount()).isEqualTo(1);
        assertThat(result.ownershipStatus()).isEqualTo(OwnershipStatus.ACTIVE);
        assertThat(result.version()).isEqualTo(20L);
        assertThat(result.replayed()).isFalse();

        ArgumentCaptor<TicketStatusHistoryEntry> historyCaptor = ArgumentCaptor.forClass(TicketStatusHistoryEntry.class);
        verify(historyWriter).append(historyCaptor.capture());
        assertThat(historyCaptor.getValue().fromStatus()).isEqualTo(TicketStatus.RESOLVED);
        assertThat(historyCaptor.getValue().toStatus()).isEqualTo(TicketStatus.IN_PROGRESS);
        assertThat(historyCaptor.getValue().transitionId()).isEqualTo("SM-012");
        assertThat(historyCaptor.getValue().reasonCode()).isEqualTo("TICKET_REOPENED");
        assertThat(historyCaptor.getValue().aggregateVersion()).isEqualTo(20L);

        ArgumentCaptor<AuditRecordEntry> auditCaptor = ArgumentCaptor.forClass(AuditRecordEntry.class);
        verify(auditRecordPort).append(auditCaptor.capture());
        assertThat(auditCaptor.getValue().action()).isEqualTo("TICKET_REOPENED");
        assertThat(auditCaptor.getValue().outcome()).isEqualTo("SUCCESS");

        ArgumentCaptor<OutboxEventEntry> outboxCaptor = ArgumentCaptor.forClass(OutboxEventEntry.class);
        verify(outboxEventRepository).append(outboxCaptor.capture());
        assertThat(outboxCaptor.getValue().eventType()).isEqualTo("ticket.reopened");
        assertThat(outboxCaptor.getValue().routingKey()).isEqualTo("ticket.reopened.v1");

        verify(idempotencyRepository).complete(any(), any());
        verify(telemetry).recordRequesterReopenCommand("success");
    }

    @Test
    void shouldUseTransitionSm013WhenReopeningFromClosed() {
        when(guardPort.loadGuard(any())).thenReturn(Optional.of(guardInStatus(TicketStatus.CLOSED, 19L)));

        ReopenTicketResult result = service.reopen(employeeCommand("key-1"));

        assertThat(result.previousStatus()).isEqualTo(TicketStatus.CLOSED);
        ArgumentCaptor<TicketStatusHistoryEntry> historyCaptor = ArgumentCaptor.forClass(TicketStatusHistoryEntry.class);
        verify(historyWriter).append(historyCaptor.capture());
        assertThat(historyCaptor.getValue().transitionId()).isEqualTo("SM-013");
    }

    @Test
    void shouldAllowAnAuthorizedSupportActorRegardlessOfRequesterIdentity() {
        RequesterReopenTicketCommand command = new RequesterReopenTicketCommand(
            TicketId.of(TICKET_ID), ReopenReasonCode.SUPPORT_REVIEW_REQUIRED, REASON, 19L,
            new ActorContext("IT_SUPPORT", "sam.support", "support-console", Set.of("ticket:reopen-request")),
            "key-support", "corr-1", "cmd-1", NOW
        );

        ReopenTicketResult result = service.reopen(command);

        assertThat(result.status()).isEqualTo(TicketStatus.IN_PROGRESS);
        assertThat(result.reopenReasonCode()).isEqualTo(ReopenReasonCode.SUPPORT_REVIEW_REQUIRED);
    }

    @Test
    void shouldReportUnassignedOwnershipStatusWhenThereIsNoCurrentAssignee() {
        when(guardPort.loadGuard(any())).thenReturn(Optional.of(new TicketRequesterReopenGuard(
            TicketId.of(TICKET_ID), TicketDisplayId.of("INC-42"), REQUESTER_ID, TicketStatus.RESOLVED, 19L,
            SupportQueueId.of(SUPPORT_QUEUE_ID), null, RESOLUTION_CYCLE_ID, ResolutionCycleStatus.RESOLVED, 1, 0
        )));

        ReopenTicketResult result = service.reopen(employeeCommand("key-1"));

        assertThat(result.ownershipStatus()).isEqualTo(OwnershipStatus.UNASSIGNED);
        verify(agentDirectoryPort, never()).findById(any());
    }

    @Test
    void shouldReturnOriginalResponseOnReplayWithoutAnySideEffects() {
        String storedJson = """
            {"ticketId":"%s","previousStatus":"RESOLVED","status":"IN_PROGRESS","previousResolutionCycleId":"%s",\
            "newResolutionCycleId":"b2b0eb44-aecf-4e4d-a77a-2b09d9eab2e8","reopenReasonCode":"REQUESTER_REPORTED_NOT_FIXED",\
            "reopenedBy":"%s","reopenedAt":"2026-08-07T21:30:00Z","reopenCount":1,"ownershipStatus":"ACTIVE","version":20}
            """.formatted(TICKET_ID, RESOLUTION_CYCLE_ID, REQUESTER_ID);
        when(idempotencyRepository.reserve(any())).thenReturn(new IdempotencyReservationOutcome.Replayed(200, storedJson));

        ReopenTicketResult result = service.reopen(employeeCommand("same-key"));

        assertThat(result.replayed()).isTrue();
        assertThat(result.version()).isEqualTo(20L);
        verify(guardPort, never()).loadGuard(any());
        verify(reopenRepository, never()).applyReopen(any());
        verify(historyWriter, never()).append(any());
        verify(auditRecordPort, never()).append(any());
        verify(outboxEventRepository, never()).append(any());
        verify(telemetry).recordRequesterReopenCommand("replay");
    }

    @Test
    void shouldRejectSameIdempotencyKeyWithADifferentPayload() {
        when(idempotencyRepository.reserve(any())).thenReturn(new IdempotencyReservationOutcome.KeyReused());

        assertThatThrownBy(() -> service.reopen(employeeCommand("same-key"))).isInstanceOf(IdempotencyKeyReusedException.class);
        verify(reopenRepository, never()).applyReopen(any());
    }

    @Test
    void shouldRejectAFreshInProgressDuplicate() {
        when(idempotencyRepository.reserve(any())).thenReturn(new IdempotencyReservationOutcome.RequestInProgress());

        assertThatThrownBy(() -> service.reopen(employeeCommand("same-key"))).isInstanceOf(RequestInProgressException.class);
        verify(reopenRepository, never()).applyReopen(any());
    }

    @Test
    void shouldRejectAnActorWithoutTheRequiredScope() {
        RequesterReopenTicketCommand command = new RequesterReopenTicketCommand(
            TicketId.of(TICKET_ID), ReopenReasonCode.REQUESTER_REPORTED_NOT_FIXED, REASON, 19L,
            new ActorContext("EMPLOYEE", REQUESTER_ID, "employee-portal", Set.of()),
            "key-1", "corr-1", "cmd-1", NOW
        );

        assertThatThrownBy(() -> service.reopen(command)).isInstanceOf(TicketAuthorizationException.class);
        verify(idempotencyRepository, never()).reserve(any());
    }

    @Test
    void shouldRejectAnEmployeeWhoIsNotTheTicketsRequester() {
        RequesterReopenTicketCommand command = new RequesterReopenTicketCommand(
            TicketId.of(TICKET_ID), ReopenReasonCode.REQUESTER_REPORTED_NOT_FIXED, REASON, 19L,
            new ActorContext("EMPLOYEE", "someone-else", "employee-portal", Set.of("ticket:reopen-request")),
            "key-1", "corr-1", "cmd-1", NOW
        );

        assertThatThrownBy(() -> service.reopen(command)).isInstanceOf(TicketAuthorizationException.class);
        verify(reopenRepository, never()).applyReopen(any());
    }

    @Test
    void shouldReturn404WhenTheTicketDoesNotExist() {
        when(guardPort.loadGuard(any())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.reopen(employeeCommand("key-1"))).isInstanceOf(TicketNotFoundException.class);
    }

    @Test
    void shouldRejectAStaleExpectedVersion() {
        when(guardPort.loadGuard(any())).thenReturn(Optional.of(guardInStatus(TicketStatus.RESOLVED, 20L)));

        assertThatThrownBy(() -> service.reopen(employeeCommand("key-1")))
            .isInstanceOfSatisfying(TicketVersionConflictException.class, ex -> assertThat(ex.currentVersion()).isEqualTo(20L));
        verify(reopenRepository, never()).applyReopen(any());
    }

    @Test
    void shouldRejectATicketWithoutACurrentResolutionCycle() {
        when(guardPort.loadGuard(any())).thenReturn(Optional.of(new TicketRequesterReopenGuard(
            TicketId.of(TICKET_ID), TicketDisplayId.of("INC-42"), REQUESTER_ID, TicketStatus.RESOLVED, 19L,
            SupportQueueId.of(SUPPORT_QUEUE_ID), ASSIGNEE_ID, null, null, 0, 0
        )));

        assertThatThrownBy(() -> service.reopen(employeeCommand("key-1"))).isInstanceOf(ResolutionCycleNotFoundException.class);
        verify(reopenRepository, never()).applyReopen(any());
    }

    @Test
    void shouldRejectAnInvalidStateDetectedAtTheRepositoryLayer() {
        doReturn(new TicketReopenUpdateOutcome.InvalidState(TicketStatus.IN_PROGRESS)).when(reopenRepository).applyReopen(any());

        assertThatThrownBy(() -> service.reopen(employeeCommand("key-1"))).isInstanceOf(InvalidTicketStateException.class);
    }

    @Test
    void shouldRejectAResolutionCycleRaceDetectedAtTheRepositoryLayer() {
        doReturn(new TicketReopenUpdateOutcome.ResolutionCycleConflict()).when(reopenRepository).applyReopen(any());

        assertThatThrownBy(() -> service.reopen(employeeCommand("key-1"))).isInstanceOf(ResolutionCycleNotFoundException.class);
    }

    @Test
    void shouldRejectATicketMissingRaceDetectedAtTheRepositoryLayer() {
        doReturn(new TicketReopenUpdateOutcome.TicketMissing()).when(reopenRepository).applyReopen(any());

        assertThatThrownBy(() -> service.reopen(employeeCommand("key-1"))).isInstanceOf(TicketNotFoundException.class);
    }

    @Test
    void shouldRejectAVersionMismatchRaceDetectedAtTheRepositoryLayer() {
        doReturn(new TicketReopenUpdateOutcome.VersionMismatch(99L)).when(reopenRepository).applyReopen(any());

        assertThatThrownBy(() -> service.reopen(employeeCommand("key-1")))
            .isInstanceOfSatisfying(TicketVersionConflictException.class, ex -> assertThat(ex.currentVersion()).isEqualTo(99L));
    }
}
