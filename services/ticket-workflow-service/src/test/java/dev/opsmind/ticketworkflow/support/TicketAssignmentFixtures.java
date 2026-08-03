package dev.opsmind.ticketworkflow.support;

import dev.opsmind.ticketworkflow.ticket.application.command.ActorContext;
import dev.opsmind.ticketworkflow.ticket.application.command.AssignTicketCommand;
import dev.opsmind.ticketworkflow.ticket.application.command.ReassignTicketCommand;
import dev.opsmind.ticketworkflow.ticket.application.command.UnassignTicketCommand;
import dev.opsmind.ticketworkflow.ticket.application.port.out.SupportAgentRecord;
import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketAssignmentGuard;
import dev.opsmind.ticketworkflow.ticket.domain.value.SupportQueueId;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketDisplayId;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketId;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketStatus;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/** Fixtures shared by the Assign/Reassign/Unassign Ticket (SPEC-TW-008) application-tier unit tests. */
public final class TicketAssignmentFixtures {

    public static final UUID DEFAULT_TICKET_ID = UUID.fromString("018f0f1e-7b31-7a00-8f42-31f9b25b1a93");
    public static final SupportQueueId DEFAULT_SUPPORT_QUEUE_ID = SupportQueueId.of(UUID.fromString("44444444-4444-4444-4444-444444444444"));
    public static final String DEFAULT_TEAM_ID = "TEAM-HOUSING";
    public static final String ASSIGN_SCOPE = "ticket:assign";
    public static final String DEFAULT_REASON = "Primary endpoint support owner";
    public static final String DEFAULT_ASSIGNEE_ID = "agent-1";
    public static final String OTHER_ASSIGNEE_ID = "agent-2";

    private TicketAssignmentFixtures() {
    }

    public static ActorContext supportActor(String subject) {
        return new ActorContext("IT_SUPPORT", subject, "support-console", Set.of(ASSIGN_SCOPE));
    }

    public static ActorContext supportActorWithoutScope(String subject) {
        return new ActorContext("IT_SUPPORT", subject, "support-console", Set.of());
    }

    public static ActorContext employeeActor(String subject) {
        return new ActorContext("EMPLOYEE", subject, "employee-portal", Set.of());
    }

    public static TicketAssignmentGuard guard(UUID ticketId, TicketStatus status, long version, String currentAssigneeId) {
        return new TicketAssignmentGuard(
            TicketId.of(ticketId), TicketDisplayId.of("INC-1"), status, version, DEFAULT_SUPPORT_QUEUE_ID, DEFAULT_TEAM_ID, currentAssigneeId
        );
    }

    public static TicketAssignmentGuard guardWithoutTeam(UUID ticketId, TicketStatus status, long version, String currentAssigneeId) {
        return new TicketAssignmentGuard(
            TicketId.of(ticketId), TicketDisplayId.of("INC-1"), status, version, DEFAULT_SUPPORT_QUEUE_ID, null, currentAssigneeId
        );
    }

    public static SupportAgentRecord activeSupportAgent(String agentId) {
        return new SupportAgentRecord(agentId, "Sam Lee", "IT_SUPPORT", true);
    }

    public static SupportAgentRecord inactiveSupportAgent(String agentId) {
        return new SupportAgentRecord(agentId, "Sam Lee", "IT_SUPPORT", false);
    }

    public static SupportAgentRecord nonSupportAgent(String agentId) {
        return new SupportAgentRecord(agentId, "Sam Lee", "EMPLOYEE", true);
    }

    public static AssignTicketCommand assignCommand(UUID ticketId, ActorContext actor, Set<String> allowedTeamIds, long expectedVersion, String idempotencyKey) {
        return assignCommand(ticketId, DEFAULT_ASSIGNEE_ID, actor, allowedTeamIds, expectedVersion, idempotencyKey);
    }

    public static AssignTicketCommand assignCommand(UUID ticketId, String assigneeId, ActorContext actor, Set<String> allowedTeamIds, long expectedVersion, String idempotencyKey) {
        return new AssignTicketCommand(
            TicketId.of(ticketId), assigneeId, DEFAULT_REASON, expectedVersion, actor, allowedTeamIds,
            idempotencyKey, "corr-" + UUID.randomUUID(), "cmd-" + UUID.randomUUID(), Instant.parse("2026-07-31T18:30:00Z")
        );
    }

    public static ReassignTicketCommand reassignCommand(UUID ticketId, ActorContext actor, Set<String> allowedTeamIds, long expectedVersion, String idempotencyKey) {
        return reassignCommand(ticketId, OTHER_ASSIGNEE_ID, actor, allowedTeamIds, expectedVersion, idempotencyKey);
    }

    public static ReassignTicketCommand reassignCommand(UUID ticketId, String assigneeId, ActorContext actor, Set<String> allowedTeamIds, long expectedVersion, String idempotencyKey) {
        return new ReassignTicketCommand(
            TicketId.of(ticketId), assigneeId, DEFAULT_REASON, expectedVersion, actor, allowedTeamIds,
            idempotencyKey, "corr-" + UUID.randomUUID(), "cmd-" + UUID.randomUUID(), Instant.parse("2026-07-31T18:30:00Z")
        );
    }

    public static UnassignTicketCommand unassignCommand(UUID ticketId, ActorContext actor, Set<String> allowedTeamIds, long expectedVersion, String idempotencyKey) {
        return new UnassignTicketCommand(
            TicketId.of(ticketId), DEFAULT_REASON, expectedVersion, actor, allowedTeamIds,
            idempotencyKey, "corr-" + UUID.randomUUID(), "cmd-" + UUID.randomUUID(), Instant.parse("2026-07-31T18:30:00Z")
        );
    }
}
