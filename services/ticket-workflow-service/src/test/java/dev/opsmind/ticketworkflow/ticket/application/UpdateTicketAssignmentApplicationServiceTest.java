package dev.opsmind.ticketworkflow.ticket.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.opsmind.ticketworkflow.ticket.application.command.ActorContext;
import dev.opsmind.ticketworkflow.ticket.application.command.UpdateTicketAssignmentCommand;
import dev.opsmind.ticketworkflow.ticket.application.command.UpdateTicketAssignmentResult;
import dev.opsmind.ticketworkflow.ticket.application.event.TicketAssignmentUpdatedEventMapper;
import dev.opsmind.ticketworkflow.ticket.application.exception.AssigneeInactiveException;
import dev.opsmind.ticketworkflow.ticket.application.exception.AssigneeNotFoundException;
import dev.opsmind.ticketworkflow.ticket.application.exception.AssigneeNotInQueueException;
import dev.opsmind.ticketworkflow.ticket.application.exception.AssigneeNotSupportAgentException;
import dev.opsmind.ticketworkflow.ticket.application.exception.IdempotencyKeyReusedException;
import dev.opsmind.ticketworkflow.ticket.application.exception.RequestInProgressException;
import dev.opsmind.ticketworkflow.ticket.application.exception.SupportQueueInvalidException;
import dev.opsmind.ticketworkflow.ticket.application.exception.TicketAuthorizationException;
import dev.opsmind.ticketworkflow.ticket.application.exception.TicketNotFoundException;
import dev.opsmind.ticketworkflow.ticket.application.exception.TicketVersionConflictException;
import dev.opsmind.ticketworkflow.ticket.application.idempotency.IdempotencyReservationOutcome;
import dev.opsmind.ticketworkflow.ticket.application.idempotency.RequestHashCalculator;
import dev.opsmind.ticketworkflow.ticket.application.model.AuditRecordEntry;
import dev.opsmind.ticketworkflow.ticket.application.model.OutboxEventEntry;
import dev.opsmind.ticketworkflow.ticket.application.model.TicketAssignmentHistoryEntry;
import dev.opsmind.ticketworkflow.ticket.application.observability.TicketTelemetry;
import dev.opsmind.ticketworkflow.ticket.application.port.out.AuditRecordPort;
import dev.opsmind.ticketworkflow.ticket.application.port.out.CatalogSupportQueue;
import dev.opsmind.ticketworkflow.ticket.application.port.out.ClockPort;
import dev.opsmind.ticketworkflow.ticket.application.port.out.IdempotencyRepository;
import dev.opsmind.ticketworkflow.ticket.application.port.out.OutboxEventRepository;
import dev.opsmind.ticketworkflow.ticket.application.port.out.SupportAgentDirectoryPort;
import dev.opsmind.ticketworkflow.ticket.application.port.out.SupportAgentRecord;
import dev.opsmind.ticketworkflow.ticket.application.port.out.SupportQueueCatalogPort;
import dev.opsmind.ticketworkflow.ticket.application.port.out.SupportQueueMembershipPort;
import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketAssignmentGuard;
import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketAssignmentGuardPort;
import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketAssignmentHistoryWriter;
import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketAssignmentRouteRepository;
import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketAssignmentRouteUpdate;
import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketAssignmentRouteUpdateOutcome;
import dev.opsmind.ticketworkflow.ticket.application.service.UpdateTicketAssignmentApplicationService;
import dev.opsmind.ticketworkflow.ticket.domain.exception.AssigneeRequiredForCurrentStatusException;
import dev.opsmind.ticketworkflow.ticket.domain.exception.InvalidTicketStateException;
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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** SPEC-TW-030: the full success transaction, assignee-eligibility pipeline, guard rejections, and idempotency outcomes. */
@Tag("unit")
class UpdateTicketAssignmentApplicationServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-07T23:00:00Z");
    private static final UUID TICKET_ID = UUID.fromString("6c2ad02e-c394-41fb-8e38-dfffd581a59d");
    private static final UUID CURRENT_QUEUE_ID = UUID.fromString("9d38b723-4a4d-47d3-94fe-32ef78cc0690");
    private static final UUID NEW_QUEUE_ID = UUID.fromString("4bde946d-60b8-4e4e-9970-6a0d0d1448f1");
    private static final String CURRENT_TEAM_ID = "TEAM-A";
    private static final String NEW_TEAM_ID = "TEAM-B";
    private static final String CURRENT_ASSIGNEE_ID = "sam.support";
    private static final String NEW_ASSIGNEE_ID = "alex.support";
    private static final String REASON = "Rebalancing queue load across teams.";

    private TicketAssignmentGuardPort guardPort;
    private SupportQueueCatalogPort supportQueueCatalogPort;
    private SupportAgentDirectoryPort agentDirectoryPort;
    private SupportQueueMembershipPort queueMembershipPort;
    private TicketAssignmentRouteRepository repository;
    private TicketAssignmentHistoryWriter assignmentHistoryWriter;
    private AuditRecordPort auditRecordPort;
    private OutboxEventRepository outboxEventRepository;
    private IdempotencyRepository idempotencyRepository;
    private ClockPort clock;
    private TicketTelemetry telemetry;
    private UpdateTicketAssignmentApplicationService service;

    @BeforeEach
    void setUp() {
        guardPort = mock(TicketAssignmentGuardPort.class);
        supportQueueCatalogPort = mock(SupportQueueCatalogPort.class);
        agentDirectoryPort = mock(SupportAgentDirectoryPort.class);
        queueMembershipPort = mock(SupportQueueMembershipPort.class);
        repository = mock(TicketAssignmentRouteRepository.class);
        assignmentHistoryWriter = mock(TicketAssignmentHistoryWriter.class);
        auditRecordPort = mock(AuditRecordPort.class);
        outboxEventRepository = mock(OutboxEventRepository.class);
        idempotencyRepository = mock(IdempotencyRepository.class);
        clock = mock(ClockPort.class);
        telemetry = mock(TicketTelemetry.class);

        when(clock.now()).thenReturn(NOW);
        when(idempotencyRepository.reserve(any())).thenReturn(new IdempotencyReservationOutcome.Reserved(UUID.randomUUID()));
        when(guardPort.loadGuard(any())).thenReturn(Optional.of(defaultGuard()));
        when(supportQueueCatalogPort.findActiveById(any())).thenReturn(Optional.of(new CatalogSupportQueue(SupportQueueId.of(NEW_QUEUE_ID), NEW_TEAM_ID, "Queue B")));
        when(agentDirectoryPort.findById(NEW_ASSIGNEE_ID)).thenReturn(Optional.of(new SupportAgentRecord(NEW_ASSIGNEE_ID, "Alex Lee", "IT_SUPPORT", true)));
        when(queueMembershipPort.isMember(anyString(), any())).thenReturn(true);
        when(repository.applyRoute(any())).thenAnswer(invocation -> {
            TicketAssignmentRouteUpdate update = invocation.getArgument(0);
            return new TicketAssignmentRouteUpdateOutcome.Updated(update.expectedVersion() + 1);
        });

        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        service = new UpdateTicketAssignmentApplicationService(
            guardPort, supportQueueCatalogPort, agentDirectoryPort, queueMembershipPort, repository, assignmentHistoryWriter,
            auditRecordPort, outboxEventRepository, idempotencyRepository, clock, new RequestHashCalculator(objectMapper),
            new TicketAssignmentUpdatedEventMapper(), telemetry, objectMapper
        );
    }

    private TicketAssignmentGuard defaultGuard() {
        return guardInStatus(TicketStatus.IN_PROGRESS, 7L);
    }

    private TicketAssignmentGuard guardInStatus(TicketStatus status, long version) {
        return new TicketAssignmentGuard(
            TicketId.of(TICKET_ID), TicketDisplayId.of("INC-42"), status, version,
            SupportQueueId.of(CURRENT_QUEUE_ID), CURRENT_TEAM_ID, CURRENT_ASSIGNEE_ID
        );
    }

    private UpdateTicketAssignmentCommand command(String idempotencyKey) {
        return new UpdateTicketAssignmentCommand(
            TicketId.of(TICKET_ID), SupportQueueId.of(NEW_QUEUE_ID), NEW_ASSIGNEE_ID, REASON, 7L,
            new ActorContext("IT_SUPPORT", "lead.sam", "support-console", Set.of("ticket:assign-route")),
            idempotencyKey, "corr-1", "cmd-1", NOW
        );
    }

    @Test
    void shouldRouteSuccessfullyAndPersistHistoryAuditOutboxAndIdempotency() {
        UpdateTicketAssignmentResult result = service.updateAssignment(command("key-1"));

        assertThat(result.status()).isEqualTo(TicketStatus.IN_PROGRESS);
        assertThat(result.teamId()).isEqualTo(NEW_TEAM_ID);
        assertThat(result.supportQueueId()).isEqualTo(SupportQueueId.of(NEW_QUEUE_ID));
        assertThat(result.assigneeId()).isEqualTo(NEW_ASSIGNEE_ID);
        assertThat(result.assigneeDisplayName()).isEqualTo("Alex Lee");
        assertThat(result.version()).isEqualTo(8L);
        assertThat(result.replayed()).isFalse();

        ArgumentCaptor<TicketAssignmentHistoryEntry> historyCaptor = ArgumentCaptor.forClass(TicketAssignmentHistoryEntry.class);
        verify(assignmentHistoryWriter).append(historyCaptor.capture());
        assertThat(historyCaptor.getValue().action()).isEqualTo("ROUTED");
        assertThat(historyCaptor.getValue().previousAssigneeId()).isEqualTo(CURRENT_ASSIGNEE_ID);
        assertThat(historyCaptor.getValue().newAssigneeId()).isEqualTo(NEW_ASSIGNEE_ID);
        assertThat(historyCaptor.getValue().resultingVersion()).isEqualTo(8L);

        ArgumentCaptor<AuditRecordEntry> auditCaptor = ArgumentCaptor.forClass(AuditRecordEntry.class);
        verify(auditRecordPort).append(auditCaptor.capture());
        assertThat(auditCaptor.getValue().action()).isEqualTo("TICKET_ASSIGNMENT_UPDATED");
        assertThat(auditCaptor.getValue().outcome()).isEqualTo("SUCCESS");

        ArgumentCaptor<OutboxEventEntry> outboxCaptor = ArgumentCaptor.forClass(OutboxEventEntry.class);
        verify(outboxEventRepository).append(outboxCaptor.capture());
        assertThat(outboxCaptor.getValue().eventType()).isEqualTo("ticket.assignment-updated");
        assertThat(outboxCaptor.getValue().routingKey()).isEqualTo("ticket.assignment-updated.v1");

        verify(idempotencyRepository).complete(any(), any());
        verify(telemetry).recordAssignmentRouteCommand("success");
    }

    @Test
    void shouldAllowClearingTheAssignee() {
        // EXECUTING is outside Ticket.STATUSES_REQUIRING_ASSIGNEE (unlike
        // IN_PROGRESS, whose V015 ck_tickets_work_states_have_assignee CHECK
        // constraint forbids a null current_support_user_id).
        when(guardPort.loadGuard(any())).thenReturn(Optional.of(guardInStatus(TicketStatus.EXECUTING, 7L)));
        UpdateTicketAssignmentCommand command = new UpdateTicketAssignmentCommand(
            TicketId.of(TICKET_ID), SupportQueueId.of(NEW_QUEUE_ID), null, REASON, 7L,
            new ActorContext("IT_SUPPORT", "lead.sam", "support-console", Set.of("ticket:assign-route")),
            "key-1", "corr-1", "cmd-1", NOW
        );

        UpdateTicketAssignmentResult result = service.updateAssignment(command);

        assertThat(result.assigneeId()).isNull();
        assertThat(result.assigneeDisplayName()).isNull();
        verify(agentDirectoryPort, never()).findById(any());
    }

    @Test
    void shouldRejectClearingTheAssigneeWhileTheStatusRequiresOne() {
        UpdateTicketAssignmentCommand command = new UpdateTicketAssignmentCommand(
            TicketId.of(TICKET_ID), SupportQueueId.of(NEW_QUEUE_ID), null, REASON, 7L,
            new ActorContext("IT_SUPPORT", "lead.sam", "support-console", Set.of("ticket:assign-route")),
            "key-1", "corr-1", "cmd-1", NOW
        );

        assertThatThrownBy(() -> service.updateAssignment(command))
            .isInstanceOf(AssigneeRequiredForCurrentStatusException.class);
    }

    @Test
    void shouldAllowASerivceRouterActorWithTheRequiredScope() {
        UpdateTicketAssignmentCommand command = new UpdateTicketAssignmentCommand(
            TicketId.of(TICKET_ID), SupportQueueId.of(NEW_QUEUE_ID), NEW_ASSIGNEE_ID, REASON, 7L,
            new ActorContext("SERVICE", "assignment-router", "assignment-router-service", Set.of("ticket:assign-route")),
            "key-1", "corr-1", "cmd-1", NOW
        );

        UpdateTicketAssignmentResult result = service.updateAssignment(command);

        assertThat(result.status()).isEqualTo(TicketStatus.IN_PROGRESS);
    }

    @Test
    void shouldReturnOriginalResponseOnReplayWithoutAnySideEffects() {
        String storedJson = """
            {"ticketId":"%s","status":"IN_PROGRESS","teamId":"%s","supportQueueId":"%s","assigneeId":"%s",\
            "assigneeDisplayName":"Alex Lee","reason":"%s","updatedBy":"lead.sam","updatedAt":"2026-08-07T23:00:00Z","version":8}
            """.formatted(TICKET_ID, NEW_TEAM_ID, NEW_QUEUE_ID, NEW_ASSIGNEE_ID, REASON);
        when(idempotencyRepository.reserve(any())).thenReturn(new IdempotencyReservationOutcome.Replayed(200, storedJson));

        UpdateTicketAssignmentResult result = service.updateAssignment(command("same-key"));

        assertThat(result.replayed()).isTrue();
        assertThat(result.version()).isEqualTo(8L);
        verify(guardPort, never()).loadGuard(any());
        verify(repository, never()).applyRoute(any());
        verify(assignmentHistoryWriter, never()).append(any());
        verify(auditRecordPort, never()).append(any());
        verify(outboxEventRepository, never()).append(any());
        verify(telemetry).recordAssignmentRouteCommand("replay");
    }

    @Test
    void shouldRejectSameIdempotencyKeyWithADifferentPayload() {
        when(idempotencyRepository.reserve(any())).thenReturn(new IdempotencyReservationOutcome.KeyReused());

        assertThatThrownBy(() -> service.updateAssignment(command("same-key"))).isInstanceOf(IdempotencyKeyReusedException.class);
        verify(repository, never()).applyRoute(any());
    }

    @Test
    void shouldRejectAFreshInProgressDuplicate() {
        when(idempotencyRepository.reserve(any())).thenReturn(new IdempotencyReservationOutcome.RequestInProgress());

        assertThatThrownBy(() -> service.updateAssignment(command("same-key"))).isInstanceOf(RequestInProgressException.class);
        verify(repository, never()).applyRoute(any());
    }

    @Test
    void shouldRejectAnActorWithoutTheRequiredScope() {
        UpdateTicketAssignmentCommand command = new UpdateTicketAssignmentCommand(
            TicketId.of(TICKET_ID), SupportQueueId.of(NEW_QUEUE_ID), NEW_ASSIGNEE_ID, REASON, 7L,
            new ActorContext("IT_SUPPORT", "lead.sam", "support-console", Set.of()),
            "key-1", "corr-1", "cmd-1", NOW
        );

        assertThatThrownBy(() -> service.updateAssignment(command)).isInstanceOf(TicketAuthorizationException.class);
        verify(idempotencyRepository, never()).reserve(any());
    }

    @Test
    void shouldReturn404WhenTheTicketDoesNotExist() {
        when(guardPort.loadGuard(any())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.updateAssignment(command("key-1"))).isInstanceOf(TicketNotFoundException.class);
    }

    @Test
    void shouldRejectAStaleExpectedVersion() {
        when(guardPort.loadGuard(any())).thenReturn(Optional.of(guardInStatus(TicketStatus.IN_PROGRESS, 8L)));

        assertThatThrownBy(() -> service.updateAssignment(command("key-1")))
            .isInstanceOfSatisfying(TicketVersionConflictException.class, ex -> assertThat(ex.currentVersion()).isEqualTo(8L));
        verify(repository, never()).applyRoute(any());
    }

    @Test
    void shouldRejectAnInvalidTargetQueue() {
        when(supportQueueCatalogPort.findActiveById(any())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.updateAssignment(command("key-1"))).isInstanceOf(SupportQueueInvalidException.class);
        verify(repository, never()).applyRoute(any());
    }

    @Test
    void shouldRejectAnUnknownAssignee() {
        when(agentDirectoryPort.findById(NEW_ASSIGNEE_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.updateAssignment(command("key-1"))).isInstanceOf(AssigneeNotFoundException.class);
    }

    @Test
    void shouldRejectAnInactiveAssignee() {
        when(agentDirectoryPort.findById(NEW_ASSIGNEE_ID)).thenReturn(Optional.of(new SupportAgentRecord(NEW_ASSIGNEE_ID, "Alex Lee", "IT_SUPPORT", false)));

        assertThatThrownBy(() -> service.updateAssignment(command("key-1"))).isInstanceOf(AssigneeInactiveException.class);
    }

    @Test
    void shouldRejectANonSupportAssignee() {
        when(agentDirectoryPort.findById(NEW_ASSIGNEE_ID)).thenReturn(Optional.of(new SupportAgentRecord(NEW_ASSIGNEE_ID, "Alex Lee", "EMPLOYEE", true)));

        assertThatThrownBy(() -> service.updateAssignment(command("key-1"))).isInstanceOf(AssigneeNotSupportAgentException.class);
    }

    @Test
    void shouldRejectAnAssigneeNotInTheTargetQueue() {
        when(queueMembershipPort.isMember(anyString(), any())).thenReturn(false);

        assertThatThrownBy(() -> service.updateAssignment(command("key-1"))).isInstanceOf(AssigneeNotInQueueException.class);
    }

    @Test
    void shouldRejectATerminalStatusDetectedAtTheRepositoryLayer() {
        doReturn(new TicketAssignmentRouteUpdateOutcome.InvalidState(TicketStatus.CLOSED)).when(repository).applyRoute(any());

        assertThatThrownBy(() -> service.updateAssignment(command("key-1"))).isInstanceOf(InvalidTicketStateException.class);
    }

    @Test
    void shouldRejectATicketMissingRaceDetectedAtTheRepositoryLayer() {
        doReturn(new TicketAssignmentRouteUpdateOutcome.TicketMissing()).when(repository).applyRoute(any());

        assertThatThrownBy(() -> service.updateAssignment(command("key-1"))).isInstanceOf(TicketNotFoundException.class);
    }

    @Test
    void shouldRejectAVersionMismatchRaceDetectedAtTheRepositoryLayer() {
        doReturn(new TicketAssignmentRouteUpdateOutcome.VersionMismatch(99L)).when(repository).applyRoute(any());

        assertThatThrownBy(() -> service.updateAssignment(command("key-1")))
            .isInstanceOfSatisfying(TicketVersionConflictException.class, ex -> assertThat(ex.currentVersion()).isEqualTo(99L));
    }
}
