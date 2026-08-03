package dev.opsmind.ticketworkflow.ticket.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.opsmind.ticketworkflow.support.TicketAssignmentFixtures;
import dev.opsmind.ticketworkflow.ticket.application.event.TicketAssignmentEventMapper;
import dev.opsmind.ticketworkflow.ticket.application.exception.QueueAccessDeniedException;
import dev.opsmind.ticketworkflow.ticket.application.exception.TicketAuthorizationException;
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

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * SPEC-TW-008 deviation #8: a single shared scope {@code ticket:assign}
 * gates all three operations. A Requester/EMPLOYEE actor is covered by the
 * plain missing-scope check (Employees never hold this scope in this
 * codebase, unlike SPEC-TW-007's Triage which has a dedicated
 * actor-type check) — there is no separate "role" rejection reason.
 */
@Tag("unit")
class TicketAssignmentAuthorizationTest {

    private static final UUID TICKET_ID = TicketAssignmentFixtures.DEFAULT_TICKET_ID;

    private TicketAssignmentGuardPort guardPort;
    private SupportAgentDirectoryPort agentDirectoryPort;
    private SupportQueueMembershipPort queueMembershipPort;
    private TicketAssignmentRepository assignmentRepository;
    private IdempotencyRepository idempotencyRepository;
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
        idempotencyRepository = mock(IdempotencyRepository.class);
        ClockPort clock = mock(ClockPort.class);
        telemetry = mock(TicketTelemetry.class);

        when(clock.now()).thenReturn(Instant.parse("2026-07-31T18:30:00Z"));
        when(idempotencyRepository.reserve(any())).thenReturn(new IdempotencyReservationOutcome.Reserved(UUID.randomUUID()));
        when(queueMembershipPort.isMember(any(), any())).thenReturn(true);
        when(assignmentRepository.applyAssignment(any())).thenAnswer(invocation -> {
            TicketAssignmentUpdate update = invocation.getArgument(0);
            return new TicketAssignmentUpdateOutcome.Updated(update.expectedVersion() + 1);
        });
        when(agentDirectoryPort.findById(any())).thenReturn(Optional.of(TicketAssignmentFixtures.activeSupportAgent("agent")));

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

        @Test
        void shouldRejectAnEmployeeActorForMissingTheAssignScope() {
            var command = TicketAssignmentFixtures.assignCommand(
                TICKET_ID, TicketAssignmentFixtures.employeeActor("employee-123"), Set.of(TicketAssignmentFixtures.DEFAULT_TEAM_ID), 7L, "key-1"
            );

            assertThatThrownBy(() -> service.assign(command)).isInstanceOf(TicketAuthorizationException.class);
            verify(idempotencyRepository, never()).reserve(any());
            verify(guardPort, never()).loadGuard(any());
            verify(telemetry).recordAssignmentAuthorizationDenied();
        }

        @Test
        void shouldRejectASupportActorMissingTheAssignScope() {
            var command = TicketAssignmentFixtures.assignCommand(
                TICKET_ID, TicketAssignmentFixtures.supportActorWithoutScope("support-100"), Set.of(TicketAssignmentFixtures.DEFAULT_TEAM_ID), 7L, "key-2"
            );

            assertThatThrownBy(() -> service.assign(command)).isInstanceOf(TicketAuthorizationException.class);
            verify(idempotencyRepository, never()).reserve(any());
        }

        @Test
        void shouldRejectAnActorHoldingTheScopeButNotGrantedTheTicketsTeam() {
            var command = TicketAssignmentFixtures.assignCommand(
                TICKET_ID, TicketAssignmentFixtures.supportActor("support-100"), Set.of("SOME-OTHER-TEAM"), 7L, "key-3"
            );

            assertThatThrownBy(() -> service.assign(command)).isInstanceOf(QueueAccessDeniedException.class);
            verify(telemetry).recordAssignmentAuthorizationDenied();
            verify(assignmentRepository, never()).applyAssignment(any());
        }

        @Test
        void shouldAllowASupportActorGrantedTheTicketsTeam() {
            var command = TicketAssignmentFixtures.assignCommand(
                TICKET_ID, TicketAssignmentFixtures.supportActor("support-100"), Set.of(TicketAssignmentFixtures.DEFAULT_TEAM_ID), 7L, "key-4"
            );

            assertThatCode(() -> service.assign(command)).doesNotThrowAnyException();
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

        @Test
        void shouldRejectAnEmployeeActorForMissingTheAssignScope() {
            var command = TicketAssignmentFixtures.reassignCommand(
                TICKET_ID, TicketAssignmentFixtures.employeeActor("employee-123"), Set.of(TicketAssignmentFixtures.DEFAULT_TEAM_ID), 7L, "key-1"
            );

            assertThatThrownBy(() -> service.reassign(command)).isInstanceOf(TicketAuthorizationException.class);
            verify(idempotencyRepository, never()).reserve(any());
        }

        @Test
        void shouldRejectAnActorHoldingTheScopeButNotGrantedTheTicketsTeam() {
            var command = TicketAssignmentFixtures.reassignCommand(
                TICKET_ID, TicketAssignmentFixtures.supportActor("support-100"), Set.of("SOME-OTHER-TEAM"), 7L, "key-2"
            );

            assertThatThrownBy(() -> service.reassign(command)).isInstanceOf(QueueAccessDeniedException.class);
            verify(assignmentRepository, never()).applyAssignment(any());
        }

        @Test
        void shouldAllowASupportActorGrantedTheTicketsTeam() {
            var command = TicketAssignmentFixtures.reassignCommand(
                TICKET_ID, TicketAssignmentFixtures.supportActor("support-100"), Set.of(TicketAssignmentFixtures.DEFAULT_TEAM_ID), 7L, "key-3"
            );

            assertThatCode(() -> service.reassign(command)).doesNotThrowAnyException();
        }
    }

    @Nested
    class Unassign {

        @BeforeEach
        void setUpGuard() {
            when(guardPort.loadGuard(any())).thenReturn(Optional.of(
                TicketAssignmentFixtures.guard(TICKET_ID, TicketStatus.ASSIGNED, 7L, TicketAssignmentFixtures.DEFAULT_ASSIGNEE_ID)
            ));
        }

        @Test
        void shouldRejectAnEmployeeActorForMissingTheAssignScope() {
            var command = TicketAssignmentFixtures.unassignCommand(
                TICKET_ID, TicketAssignmentFixtures.employeeActor("employee-123"), Set.of(TicketAssignmentFixtures.DEFAULT_TEAM_ID), 7L, "key-1"
            );

            assertThatThrownBy(() -> service.unassign(command)).isInstanceOf(TicketAuthorizationException.class);
            verify(idempotencyRepository, never()).reserve(any());
        }

        @Test
        void shouldRejectAnActorHoldingTheScopeButNotGrantedTheTicketsTeam() {
            var command = TicketAssignmentFixtures.unassignCommand(
                TICKET_ID, TicketAssignmentFixtures.supportActor("support-100"), Set.of("SOME-OTHER-TEAM"), 7L, "key-2"
            );

            assertThatThrownBy(() -> service.unassign(command)).isInstanceOf(QueueAccessDeniedException.class);
            verify(assignmentRepository, never()).applyAssignment(any());
        }

        @Test
        void shouldAllowASupportActorGrantedTheTicketsTeam() {
            var command = TicketAssignmentFixtures.unassignCommand(
                TICKET_ID, TicketAssignmentFixtures.supportActor("support-100"), Set.of(TicketAssignmentFixtures.DEFAULT_TEAM_ID), 7L, "key-3"
            );

            assertThatCode(() -> service.unassign(command)).doesNotThrowAnyException();
        }
    }
}
