package dev.opsmind.ticketworkflow.ticket.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.opsmind.ticketworkflow.support.TicketAssignmentFixtures;
import dev.opsmind.ticketworkflow.ticket.application.command.AssignTicketCommand;
import dev.opsmind.ticketworkflow.ticket.application.command.ReassignTicketCommand;
import dev.opsmind.ticketworkflow.ticket.application.event.TicketAssignmentEventMapper;
import dev.opsmind.ticketworkflow.ticket.application.exception.AssigneeInactiveException;
import dev.opsmind.ticketworkflow.ticket.application.exception.AssigneeNotFoundException;
import dev.opsmind.ticketworkflow.ticket.application.exception.AssigneeNotInQueueException;
import dev.opsmind.ticketworkflow.ticket.application.exception.AssigneeNotSupportAgentException;
import dev.opsmind.ticketworkflow.ticket.application.idempotency.IdempotencyReservationOutcome;
import dev.opsmind.ticketworkflow.ticket.application.idempotency.RequestHashCalculator;
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

import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * SPEC-TW-008 §6/AC-05: the 4-step assignee eligibility chain
 * ({@code TicketAssignmentApplicationService#resolveEligibleAssignee}) —
 * exercised by both Assign and Reassign (Unassign never calls it, see
 * {@code TicketAssignmentApplicationServiceTest.UnassignSuccess}). The
 * not-support-agent branch is unreachable through the real database adapter
 * (the {@code support_agents.role} CHECK constraint rules it out) and is
 * ONLY exercisable through a mocked port, which is exactly what this test
 * does — mirrors {@code TriageTicketCatalogValidationTest}'s structure.
 */
@Tag("unit")
class TicketAssignmentEligibilityTest {

    private static final UUID TICKET_ID = TicketAssignmentFixtures.DEFAULT_TICKET_ID;
    private static final String ASSIGNEE_ID = TicketAssignmentFixtures.DEFAULT_ASSIGNEE_ID;

    private TicketAssignmentGuardPort guardPort;
    private SupportAgentDirectoryPort agentDirectoryPort;
    private SupportQueueMembershipPort queueMembershipPort;
    private TicketAssignmentRepository assignmentRepository;
    private TicketTelemetry telemetry;
    private TicketAssignmentApplicationService service;

    @BeforeEach
    void setUp() {
        guardPort = mock(TicketAssignmentGuardPort.class);
        agentDirectoryPort = mock(SupportAgentDirectoryPort.class);
        queueMembershipPort = mock(SupportQueueMembershipPort.class);
        assignmentRepository = mock(TicketAssignmentRepository.class);
        TicketAssignmentHistoryWriter assignmentHistoryWriter = mock(TicketAssignmentHistoryWriter.class);
        TicketHistoryWriter statusHistoryWriter = mock(TicketHistoryWriter.class);
        AuditRecordPort auditRecordPort = mock(AuditRecordPort.class);
        OutboxEventRepository outboxEventRepository = mock(OutboxEventRepository.class);
        IdempotencyRepository idempotencyRepository = mock(IdempotencyRepository.class);
        ClockPort clock = mock(ClockPort.class);
        telemetry = mock(TicketTelemetry.class);

        when(clock.now()).thenReturn(Instant.parse("2026-07-31T18:30:00Z"));
        when(idempotencyRepository.reserve(any())).thenReturn(new IdempotencyReservationOutcome.Reserved(UUID.randomUUID()));
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
    class Assign {

        @BeforeEach
        void setUpGuard() {
            when(guardPort.loadGuard(any())).thenReturn(Optional.of(
                TicketAssignmentFixtures.guard(TICKET_ID, TicketStatus.TRIAGED, 7L, null)
            ));
        }

        private AssignTicketCommand command(String idempotencyKey) {
            return TicketAssignmentFixtures.assignCommand(
                TICKET_ID, ASSIGNEE_ID, TicketAssignmentFixtures.supportActor("support-100"),
                Set.of(TicketAssignmentFixtures.DEFAULT_TEAM_ID), 7L, idempotencyKey
            );
        }

        @Test
        void shouldRejectAMissingAssigneeWithNoMutationAttempted() {
            when(agentDirectoryPort.findById(ASSIGNEE_ID)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.assign(command("key-1"))).isInstanceOf(AssigneeNotFoundException.class);
            verify(telemetry).recordAssignmentEligibilityDenied("not_found");
            verify(assignmentRepository, never()).applyAssignment(any());
        }

        @Test
        void shouldRejectAnInactiveAssignee() {
            when(agentDirectoryPort.findById(ASSIGNEE_ID)).thenReturn(Optional.of(TicketAssignmentFixtures.inactiveSupportAgent(ASSIGNEE_ID)));

            assertThatThrownBy(() -> service.assign(command("key-2"))).isInstanceOf(AssigneeInactiveException.class);
            verify(telemetry).recordAssignmentEligibilityDenied("inactive");
            verify(assignmentRepository, never()).applyAssignment(any());
        }

        @Test
        void shouldRejectAnAssigneeWithoutASupportCapableRole() {
            when(agentDirectoryPort.findById(ASSIGNEE_ID)).thenReturn(Optional.of(TicketAssignmentFixtures.nonSupportAgent(ASSIGNEE_ID)));

            assertThatThrownBy(() -> service.assign(command("key-3"))).isInstanceOf(AssigneeNotSupportAgentException.class);
            verify(telemetry).recordAssignmentEligibilityDenied("not_support_agent");
            verify(assignmentRepository, never()).applyAssignment(any());
        }

        @Test
        void shouldRejectAnAssigneeNotAMemberOfTheTicketsSupportQueue() {
            when(agentDirectoryPort.findById(ASSIGNEE_ID)).thenReturn(Optional.of(TicketAssignmentFixtures.activeSupportAgent(ASSIGNEE_ID)));
            when(queueMembershipPort.isMember(any(), any())).thenReturn(false);

            assertThatThrownBy(() -> service.assign(command("key-4"))).isInstanceOf(AssigneeNotInQueueException.class);
            verify(telemetry).recordAssignmentEligibilityDenied("not_in_queue");
            verify(assignmentRepository, never()).applyAssignment(any());
        }
    }

    @Nested
    class Reassign {

        @BeforeEach
        void setUpGuard() {
            when(guardPort.loadGuard(any())).thenReturn(Optional.of(
                TicketAssignmentFixtures.guard(TICKET_ID, TicketStatus.ASSIGNED, 7L, TicketAssignmentFixtures.DEFAULT_ASSIGNEE_ID)
            ));
        }

        private ReassignTicketCommand command(String idempotencyKey) {
            return TicketAssignmentFixtures.reassignCommand(
                TICKET_ID, TicketAssignmentFixtures.OTHER_ASSIGNEE_ID, TicketAssignmentFixtures.supportActor("support-100"),
                Set.of(TicketAssignmentFixtures.DEFAULT_TEAM_ID), 7L, idempotencyKey
            );
        }

        @Test
        void shouldRejectAMissingNewAssignee() {
            when(agentDirectoryPort.findById(TicketAssignmentFixtures.OTHER_ASSIGNEE_ID)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.reassign(command("key-1"))).isInstanceOf(AssigneeNotFoundException.class);
            verify(telemetry).recordAssignmentEligibilityDenied("not_found");
            verify(assignmentRepository, never()).applyAssignment(any());
        }

        @Test
        void shouldRejectAnInactiveNewAssignee() {
            when(agentDirectoryPort.findById(TicketAssignmentFixtures.OTHER_ASSIGNEE_ID))
                .thenReturn(Optional.of(TicketAssignmentFixtures.inactiveSupportAgent(TicketAssignmentFixtures.OTHER_ASSIGNEE_ID)));

            assertThatThrownBy(() -> service.reassign(command("key-2"))).isInstanceOf(AssigneeInactiveException.class);
            verify(telemetry).recordAssignmentEligibilityDenied("inactive");
        }

        @Test
        void shouldRejectANewAssigneeWithoutASupportCapableRole() {
            when(agentDirectoryPort.findById(TicketAssignmentFixtures.OTHER_ASSIGNEE_ID))
                .thenReturn(Optional.of(TicketAssignmentFixtures.nonSupportAgent(TicketAssignmentFixtures.OTHER_ASSIGNEE_ID)));

            assertThatThrownBy(() -> service.reassign(command("key-3"))).isInstanceOf(AssigneeNotSupportAgentException.class);
            verify(telemetry).recordAssignmentEligibilityDenied("not_support_agent");
        }

        @Test
        void shouldRejectANewAssigneeNotAMemberOfTheTicketsSupportQueue() {
            when(agentDirectoryPort.findById(TicketAssignmentFixtures.OTHER_ASSIGNEE_ID))
                .thenReturn(Optional.of(TicketAssignmentFixtures.activeSupportAgent(TicketAssignmentFixtures.OTHER_ASSIGNEE_ID)));
            when(queueMembershipPort.isMember(any(), any())).thenReturn(false);

            assertThatThrownBy(() -> service.reassign(command("key-4"))).isInstanceOf(AssigneeNotInQueueException.class);
            verify(telemetry).recordAssignmentEligibilityDenied("not_in_queue");
            verify(assignmentRepository, never()).applyAssignment(any());
        }
    }
}
