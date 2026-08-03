package dev.opsmind.ticketworkflow.ticket.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.opsmind.ticketworkflow.support.TicketAssignmentFixtures;
import dev.opsmind.ticketworkflow.ticket.application.command.AssignTicketCommand;
import dev.opsmind.ticketworkflow.ticket.application.command.ReassignTicketCommand;
import dev.opsmind.ticketworkflow.ticket.application.command.TicketAssignmentResult;
import dev.opsmind.ticketworkflow.ticket.application.command.UnassignTicketCommand;
import dev.opsmind.ticketworkflow.ticket.application.event.TicketAssignmentEventMapper;
import dev.opsmind.ticketworkflow.ticket.application.exception.IdempotencyKeyReusedException;
import dev.opsmind.ticketworkflow.ticket.application.exception.RequestInProgressException;
import dev.opsmind.ticketworkflow.ticket.application.idempotency.IdempotencyReservationOutcome;
import dev.opsmind.ticketworkflow.ticket.application.idempotency.RequestHashCalculator;
import dev.opsmind.ticketworkflow.ticket.application.model.AuditRecordEntry;
import dev.opsmind.ticketworkflow.ticket.application.model.OutboxEventEntry;
import dev.opsmind.ticketworkflow.ticket.application.model.TicketAssignmentHistoryEntry;
import dev.opsmind.ticketworkflow.ticket.application.model.TicketStatusHistoryEntry;
import dev.opsmind.ticketworkflow.ticket.application.observability.TicketTelemetry;
import dev.opsmind.ticketworkflow.ticket.application.port.out.AuditRecordPort;
import dev.opsmind.ticketworkflow.ticket.application.port.out.ClockPort;
import dev.opsmind.ticketworkflow.ticket.application.port.out.IdempotencyRepository;
import dev.opsmind.ticketworkflow.ticket.application.port.out.OutboxEventRepository;
import dev.opsmind.ticketworkflow.ticket.application.port.out.SupportAgentDirectoryPort;
import dev.opsmind.ticketworkflow.ticket.application.port.out.SupportQueueMembershipPort;
import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketAssignmentGuardPort;
import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketAssignmentHistoryWriter;
import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketAssignmentRepository;
import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketAssignmentUpdate;
import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketAssignmentUpdateOutcome;
import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketHistoryWriter;
import dev.opsmind.ticketworkflow.ticket.application.service.TicketAssignmentApplicationService;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
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

/**
 * SPEC-TW-008: the full success transaction for assign/reassign/unassign,
 * plus the shared idempotency reserve/replay/conflict/in-progress outcomes.
 * Mirrors {@code TriageTicketApplicationServiceTest}'s structure.
 */
@Tag("unit")
class TicketAssignmentApplicationServiceTest {

    private static final Instant NOW = Instant.parse("2026-07-31T18:30:00Z");
    private static final UUID TICKET_ID = TicketAssignmentFixtures.DEFAULT_TICKET_ID;

    private TicketAssignmentGuardPort guardPort;
    private SupportAgentDirectoryPort agentDirectoryPort;
    private SupportQueueMembershipPort queueMembershipPort;
    private TicketAssignmentRepository assignmentRepository;
    private TicketAssignmentHistoryWriter assignmentHistoryWriter;
    private TicketHistoryWriter statusHistoryWriter;
    private AuditRecordPort auditRecordPort;
    private OutboxEventRepository outboxEventRepository;
    private IdempotencyRepository idempotencyRepository;
    private ClockPort clock;
    private TicketTelemetry telemetry;
    private TicketAssignmentApplicationService service;

    @BeforeEach
    void setUp() {
        guardPort = mock(TicketAssignmentGuardPort.class);
        agentDirectoryPort = mock(SupportAgentDirectoryPort.class);
        queueMembershipPort = mock(SupportQueueMembershipPort.class);
        assignmentRepository = mock(TicketAssignmentRepository.class);
        assignmentHistoryWriter = mock(TicketAssignmentHistoryWriter.class);
        statusHistoryWriter = mock(TicketHistoryWriter.class);
        auditRecordPort = mock(AuditRecordPort.class);
        outboxEventRepository = mock(OutboxEventRepository.class);
        idempotencyRepository = mock(IdempotencyRepository.class);
        clock = mock(ClockPort.class);
        telemetry = mock(TicketTelemetry.class);

        when(clock.now()).thenReturn(NOW);
        when(idempotencyRepository.reserve(any())).thenReturn(new IdempotencyReservationOutcome.Reserved(UUID.randomUUID()));
        when(queueMembershipPort.isMember(any(), any())).thenReturn(true);
        when(assignmentRepository.applyAssignment(any())).thenAnswer(invocation -> {
            TicketAssignmentUpdate update = invocation.getArgument(0);
            return new TicketAssignmentUpdateOutcome.Updated(update.expectedVersion() + 1);
        });

        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        service = new TicketAssignmentApplicationService(
            guardPort, agentDirectoryPort, queueMembershipPort, assignmentRepository, assignmentHistoryWriter,
            statusHistoryWriter, auditRecordPort, outboxEventRepository, idempotencyRepository, clock,
            new RequestHashCalculator(objectMapper), new TicketAssignmentEventMapper(), telemetry, objectMapper
        );
    }

    @Nested
    class AssignSuccess {

        @BeforeEach
        void setUpAssignGuard() {
            when(guardPort.loadGuard(any())).thenReturn(Optional.of(
                TicketAssignmentFixtures.guard(TICKET_ID, TicketStatus.TRIAGED, 7L, null)
            ));
            when(agentDirectoryPort.findById(TicketAssignmentFixtures.DEFAULT_ASSIGNEE_ID))
                .thenReturn(Optional.of(TicketAssignmentFixtures.activeSupportAgent(TicketAssignmentFixtures.DEFAULT_ASSIGNEE_ID)));
        }

        private AssignTicketCommand command(String idempotencyKey) {
            return TicketAssignmentFixtures.assignCommand(
                TICKET_ID, TicketAssignmentFixtures.supportActor("support-100"), Set.of(TicketAssignmentFixtures.DEFAULT_TEAM_ID), 7L, idempotencyKey
            );
        }

        @Test
        void shouldAssignSuccessfullyAndPersistHistoryStatusHistoryAuditOutboxAndIdempotency() {
            TicketAssignmentResult result = service.assign(command("key-1"));

            assertThat(result.status()).isEqualTo(TicketStatus.ASSIGNED);
            assertThat(result.assigneeId()).isEqualTo(TicketAssignmentFixtures.DEFAULT_ASSIGNEE_ID);
            assertThat(result.assigneeDisplayName()).isEqualTo("Sam Lee");
            assertThat(result.assignedAt()).isEqualTo(NOW);
            assertThat(result.version()).isEqualTo(8L);
            assertThat(result.idempotencyReplayed()).isFalse();

            ArgumentCaptor<TicketAssignmentHistoryEntry> historyCaptor = ArgumentCaptor.forClass(TicketAssignmentHistoryEntry.class);
            verify(assignmentHistoryWriter).append(historyCaptor.capture());
            assertThat(historyCaptor.getValue().action()).isEqualTo("ASSIGNED");
            assertThat(historyCaptor.getValue().previousAssigneeId()).isNull();
            assertThat(historyCaptor.getValue().newAssigneeId()).isEqualTo(TicketAssignmentFixtures.DEFAULT_ASSIGNEE_ID);
            assertThat(historyCaptor.getValue().previousStatus()).isEqualTo(TicketStatus.TRIAGED);
            assertThat(historyCaptor.getValue().newStatus()).isEqualTo(TicketStatus.ASSIGNED);
            assertThat(historyCaptor.getValue().resultingVersion()).isEqualTo(8L);

            ArgumentCaptor<TicketStatusHistoryEntry> statusHistoryCaptor = ArgumentCaptor.forClass(TicketStatusHistoryEntry.class);
            verify(statusHistoryWriter).append(statusHistoryCaptor.capture());
            assertThat(statusHistoryCaptor.getValue().fromStatus()).isEqualTo(TicketStatus.TRIAGED);
            assertThat(statusHistoryCaptor.getValue().toStatus()).isEqualTo(TicketStatus.ASSIGNED);
            assertThat(statusHistoryCaptor.getValue().transitionId()).isEqualTo("SM-003");
            assertThat(statusHistoryCaptor.getValue().reasonCode()).isEqualTo("TICKET_ASSIGNED");
            assertThat(statusHistoryCaptor.getValue().aggregateVersion()).isEqualTo(8L);

            ArgumentCaptor<AuditRecordEntry> auditCaptor = ArgumentCaptor.forClass(AuditRecordEntry.class);
            verify(auditRecordPort).append(auditCaptor.capture());
            assertThat(auditCaptor.getValue().action()).isEqualTo("TICKET_ASSIGNED");
            assertThat(auditCaptor.getValue().outcome()).isEqualTo("SUCCESS");
            assertThat(auditCaptor.getValue().ticketStatusBefore()).isEqualTo("TRIAGED");
            assertThat(auditCaptor.getValue().ticketStatusAfter()).isEqualTo("ASSIGNED");

            ArgumentCaptor<OutboxEventEntry> outboxCaptor = ArgumentCaptor.forClass(OutboxEventEntry.class);
            verify(outboxEventRepository).append(outboxCaptor.capture());
            assertThat(outboxCaptor.getValue().eventType()).isEqualTo("ticket.assigned");
            assertThat(outboxCaptor.getValue().eventVersion()).isEqualTo("1.0");
            assertThat(outboxCaptor.getValue().routingKey()).isEqualTo("ticket.assigned.v1");

            verify(idempotencyRepository).complete(any(), any());
            verify(telemetry).recordAssignmentCommand("assign", "success");
        }

        @Test
        void shouldReturnOriginalResponseOnReplayWithoutAnySideEffects() {
            String storedJson = """
                {"ticketId":"%s","status":"ASSIGNED","assigneeId":"agent-1","assigneeDisplayName":"Sam Lee",\
                "assignedAt":"2026-07-31T18:00:00Z","version":8}
                """.formatted(TICKET_ID);
            when(idempotencyRepository.reserve(any())).thenReturn(new IdempotencyReservationOutcome.Replayed(200, storedJson));

            TicketAssignmentResult result = service.assign(command("same-key"));

            assertThat(result.idempotencyReplayed()).isTrue();
            assertThat(result.version()).isEqualTo(8L);
            verify(guardPort, never()).loadGuard(any());
            verify(assignmentRepository, never()).applyAssignment(any());
            verify(assignmentHistoryWriter, never()).append(any());
            verify(statusHistoryWriter, never()).append(any());
            verify(auditRecordPort, never()).append(any());
            verify(outboxEventRepository, never()).append(any());
            verify(telemetry).recordAssignmentCommand("assign", "replay");
        }

        @Test
        void shouldRejectSameIdempotencyKeyWithADifferentPayload() {
            when(idempotencyRepository.reserve(any())).thenReturn(new IdempotencyReservationOutcome.KeyReused());

            assertThatThrownBy(() -> service.assign(command("same-key"))).isInstanceOf(IdempotencyKeyReusedException.class);
            verify(assignmentRepository, never()).applyAssignment(any());
        }

        @Test
        void shouldRejectAFreshInProgressDuplicate() {
            when(idempotencyRepository.reserve(any())).thenReturn(new IdempotencyReservationOutcome.RequestInProgress());

            assertThatThrownBy(() -> service.assign(command("same-key"))).isInstanceOf(RequestInProgressException.class);
            verify(assignmentRepository, never()).applyAssignment(any());
        }
    }

    @Nested
    class ReassignSuccess {

        @BeforeEach
        void setUpReassignGuard() {
            when(guardPort.loadGuard(any())).thenReturn(Optional.of(
                TicketAssignmentFixtures.guard(TICKET_ID, TicketStatus.ASSIGNED, 7L, TicketAssignmentFixtures.DEFAULT_ASSIGNEE_ID)
            ));
            when(agentDirectoryPort.findById(TicketAssignmentFixtures.OTHER_ASSIGNEE_ID))
                .thenReturn(Optional.of(TicketAssignmentFixtures.activeSupportAgent(TicketAssignmentFixtures.OTHER_ASSIGNEE_ID)));
        }

        private ReassignTicketCommand command(String idempotencyKey) {
            return TicketAssignmentFixtures.reassignCommand(
                TICKET_ID, TicketAssignmentFixtures.supportActor("support-100"), Set.of(TicketAssignmentFixtures.DEFAULT_TEAM_ID), 7L, idempotencyKey
            );
        }

        @Test
        void shouldReassignSuccessfullyPreservingStatusAndNeverTouchingStatusHistory() {
            TicketAssignmentResult result = service.reassign(command("key-1"));

            assertThat(result.status()).isEqualTo(TicketStatus.ASSIGNED);
            assertThat(result.assigneeId()).isEqualTo(TicketAssignmentFixtures.OTHER_ASSIGNEE_ID);
            assertThat(result.assigneeDisplayName()).isEqualTo("Sam Lee");
            assertThat(result.version()).isEqualTo(8L);

            ArgumentCaptor<TicketAssignmentHistoryEntry> historyCaptor = ArgumentCaptor.forClass(TicketAssignmentHistoryEntry.class);
            verify(assignmentHistoryWriter).append(historyCaptor.capture());
            assertThat(historyCaptor.getValue().action()).isEqualTo("REASSIGNED");
            assertThat(historyCaptor.getValue().previousAssigneeId()).isEqualTo(TicketAssignmentFixtures.DEFAULT_ASSIGNEE_ID);
            assertThat(historyCaptor.getValue().newAssigneeId()).isEqualTo(TicketAssignmentFixtures.OTHER_ASSIGNEE_ID);
            assertThat(historyCaptor.getValue().previousStatus()).isEqualTo(TicketStatus.ASSIGNED);
            assertThat(historyCaptor.getValue().newStatus()).isEqualTo(TicketStatus.ASSIGNED);

            // SPEC-TW-008 persistence §3: reassignment never writes a status-history row (status is unchanged).
            verify(statusHistoryWriter, never()).append(any());

            ArgumentCaptor<AuditRecordEntry> auditCaptor = ArgumentCaptor.forClass(AuditRecordEntry.class);
            verify(auditRecordPort).append(auditCaptor.capture());
            assertThat(auditCaptor.getValue().action()).isEqualTo("TICKET_REASSIGNED");
            assertThat(auditCaptor.getValue().ticketStatusBefore()).isEqualTo("ASSIGNED");
            assertThat(auditCaptor.getValue().ticketStatusAfter()).isEqualTo("ASSIGNED");

            ArgumentCaptor<OutboxEventEntry> outboxCaptor = ArgumentCaptor.forClass(OutboxEventEntry.class);
            verify(outboxEventRepository).append(outboxCaptor.capture());
            assertThat(outboxCaptor.getValue().eventType()).isEqualTo("ticket.reassigned");
            assertThat(outboxCaptor.getValue().routingKey()).isEqualTo("ticket.reassigned.v1");

            verify(telemetry).recordAssignmentCommand("reassign", "success");
        }

        @Test
        void shouldReassignSuccessfullyFromAWaitingStatusPreservingIt() {
            when(guardPort.loadGuard(any())).thenReturn(Optional.of(
                TicketAssignmentFixtures.guard(TICKET_ID, TicketStatus.WAITING_FOR_USER, 7L, TicketAssignmentFixtures.DEFAULT_ASSIGNEE_ID)
            ));

            TicketAssignmentResult result = service.reassign(command("key-2"));

            assertThat(result.status()).isEqualTo(TicketStatus.WAITING_FOR_USER);
            verify(statusHistoryWriter, never()).append(any());
        }

        @Test
        void shouldReturnOriginalResponseOnReplayWithoutAnySideEffects() {
            String storedJson = """
                {"ticketId":"%s","status":"ASSIGNED","assigneeId":"agent-2","assigneeDisplayName":"Sam Lee",\
                "assignedAt":"2026-07-31T18:00:00Z","version":8}
                """.formatted(TICKET_ID);
            when(idempotencyRepository.reserve(any())).thenReturn(new IdempotencyReservationOutcome.Replayed(200, storedJson));

            TicketAssignmentResult result = service.reassign(command("same-key"));

            assertThat(result.idempotencyReplayed()).isTrue();
            verify(assignmentRepository, never()).applyAssignment(any());
            verify(telemetry).recordAssignmentCommand("reassign", "replay");
        }
    }

    @Nested
    class UnassignSuccess {

        @BeforeEach
        void setUpUnassignGuard() {
            when(guardPort.loadGuard(any())).thenReturn(Optional.of(
                TicketAssignmentFixtures.guard(TICKET_ID, TicketStatus.ASSIGNED, 7L, TicketAssignmentFixtures.DEFAULT_ASSIGNEE_ID)
            ));
        }

        private UnassignTicketCommand command(String idempotencyKey) {
            return TicketAssignmentFixtures.unassignCommand(
                TICKET_ID, TicketAssignmentFixtures.supportActor("support-100"), Set.of(TicketAssignmentFixtures.DEFAULT_TEAM_ID), 7L, idempotencyKey
            );
        }

        @Test
        void shouldUnassignSuccessfullyAndNeverConsultTheAgentDirectory() {
            TicketAssignmentResult result = service.unassign(command("key-1"));

            assertThat(result.status()).isEqualTo(TicketStatus.TRIAGED);
            assertThat(result.assigneeId()).isNull();
            assertThat(result.assigneeDisplayName()).isNull();
            assertThat(result.assignedAt()).isNull();
            assertThat(result.version()).isEqualTo(8L);

            // Unassign never needs eligibility resolution (deviation #5: only assign/reassign do).
            verify(agentDirectoryPort, never()).findById(any());
            verify(queueMembershipPort, never()).isMember(any(), any());

            ArgumentCaptor<TicketAssignmentHistoryEntry> historyCaptor = ArgumentCaptor.forClass(TicketAssignmentHistoryEntry.class);
            verify(assignmentHistoryWriter).append(historyCaptor.capture());
            assertThat(historyCaptor.getValue().action()).isEqualTo("UNASSIGNED");
            assertThat(historyCaptor.getValue().previousAssigneeId()).isEqualTo(TicketAssignmentFixtures.DEFAULT_ASSIGNEE_ID);
            assertThat(historyCaptor.getValue().newAssigneeId()).isNull();
            assertThat(historyCaptor.getValue().previousStatus()).isEqualTo(TicketStatus.ASSIGNED);
            assertThat(historyCaptor.getValue().newStatus()).isEqualTo(TicketStatus.TRIAGED);

            ArgumentCaptor<TicketStatusHistoryEntry> statusHistoryCaptor = ArgumentCaptor.forClass(TicketStatusHistoryEntry.class);
            verify(statusHistoryWriter).append(statusHistoryCaptor.capture());
            assertThat(statusHistoryCaptor.getValue().fromStatus()).isEqualTo(TicketStatus.ASSIGNED);
            assertThat(statusHistoryCaptor.getValue().toStatus()).isEqualTo(TicketStatus.TRIAGED);
            assertThat(statusHistoryCaptor.getValue().transitionId()).isEqualTo("SM-004");
            assertThat(statusHistoryCaptor.getValue().reasonCode()).isEqualTo("TICKET_UNASSIGNED");

            ArgumentCaptor<AuditRecordEntry> auditCaptor = ArgumentCaptor.forClass(AuditRecordEntry.class);
            verify(auditRecordPort).append(auditCaptor.capture());
            assertThat(auditCaptor.getValue().action()).isEqualTo("TICKET_UNASSIGNED");

            ArgumentCaptor<OutboxEventEntry> outboxCaptor = ArgumentCaptor.forClass(OutboxEventEntry.class);
            verify(outboxEventRepository).append(outboxCaptor.capture());
            assertThat(outboxCaptor.getValue().eventType()).isEqualTo("ticket.unassigned");
            assertThat(outboxCaptor.getValue().routingKey()).isEqualTo("ticket.unassigned.v1");

            verify(telemetry).recordAssignmentCommand("unassign", "success");
        }

        @Test
        void shouldReturnOriginalResponseOnReplayWithoutAnySideEffects() {
            String storedJson = """
                {"ticketId":"%s","status":"TRIAGED","assigneeId":null,"assigneeDisplayName":null,\
                "assignedAt":null,"version":8}
                """.formatted(TICKET_ID);
            when(idempotencyRepository.reserve(any())).thenReturn(new IdempotencyReservationOutcome.Replayed(200, storedJson));

            TicketAssignmentResult result = service.unassign(command("same-key"));

            assertThat(result.idempotencyReplayed()).isTrue();
            assertThat(result.assigneeId()).isNull();
            verify(assignmentRepository, never()).applyAssignment(any());
            verify(telemetry).recordAssignmentCommand("unassign", "replay");
        }
    }
}
