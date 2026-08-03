package dev.opsmind.ticketworkflow.ticket.persistence;

import dev.opsmind.ticketworkflow.support.AbstractTicketAssignmentIT;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SPEC-TW-008 AC-04: state guards for assign/reassign/unassign.
 *
 * <p>Note on {@code TICKET_ALREADY_ASSIGNED}: this codebase's DB CHECK
 * constraint {@code ck_tickets_triaged_no_assignee} makes "a TRIAGED ticket
 * that already carries an assignee" impossible to seed via direct SQL
 * without violating the schema — the aggregate's defensive {@code
 * TicketAlreadyAssignedException} guard is real (see {@code
 * TicketAssignmentTest}/{@code TicketAssignmentStateGuardTest} for its unit
 * coverage) but genuinely unreachable through this integration harness
 * without corrupting the schema invariant it protects. No test forces it
 * here; see the final report.
 *
 * <p>Note on reassigning a literally-{@code TRIAGED} ticket: the same
 * invariant means a TRIAGED ticket never has a current assignee, so
 * Reassign's "has a current assignee" check ({@code TicketNotAssignedException})
 * fires before its state check ever could — asserted below as {@code
 * TICKET_NOT_ASSIGNED}, not {@code INVALID_TICKET_STATE}. The multi-status
 * {@code INVALID_TICKET_STATE} shape for Reassign is instead reached from a
 * {@code RESOLVED} ticket that still carries an assignee (a status/assignee
 * combination the schema does not forbid).
 */
@Tag("integration")
class TicketAssignmentStateGuardIT extends AbstractTicketAssignmentIT {

    @Test
    void shouldReturn409InvalidTicketStateWhenAssigningANonTriagedTicket() {
        UUID queueId = seedSupportQueue(DEFAULT_TEAM_ID, true);
        UUID ticketId = seedTicketInStatus(UUID.randomUUID(), "NEW", DEFAULT_TEAM_ID, queueId);
        seedSupportAgent(DEFAULT_ASSIGNEE_ID, "IT_SUPPORT", true);
        seedQueueMembership(DEFAULT_ASSIGNEE_ID, queueId);

        ResponseEntity<String> response = assign(
            ticketId, supportToken("support-100", Set.of(DEFAULT_TEAM_ID)), "\"0\"", UUID.randomUUID().toString(),
            assignRequestBody(DEFAULT_ASSIGNEE_ID, DEFAULT_REASON)
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).contains("INVALID_TICKET_STATE");
        assertThat(response.getBody()).contains("\"currentStatus\":\"NEW\"");
        assertThat(response.getBody()).contains("\"requiredStatus\":\"TRIAGED\"");
    }

    @Test
    void shouldReturn409TicketNotAssignedWhenReassigningALiterallyTriagedTicket() {
        UUID queueId = seedSupportQueue(DEFAULT_TEAM_ID, true);
        UUID ticketId = seedTriagedTicketReadyToAssign(DEFAULT_TEAM_ID, queueId);
        seedSupportAgent(DEFAULT_ASSIGNEE_ID, "IT_SUPPORT", true);
        seedQueueMembership(DEFAULT_ASSIGNEE_ID, queueId);

        ResponseEntity<String> response = reassign(
            ticketId, supportToken("support-100", Set.of(DEFAULT_TEAM_ID)), "\"0\"", UUID.randomUUID().toString(),
            assignRequestBody(DEFAULT_ASSIGNEE_ID, DEFAULT_REASON)
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).contains("TICKET_NOT_ASSIGNED");
    }

    @Test
    void shouldReturn409InvalidTicketStateWithAllowedStatusesWhenReassigningAResolvedTicketWithAnAssignee() {
        UUID queueId = seedSupportQueue(DEFAULT_TEAM_ID, true);
        UUID ticketId = seedResolvedTicketWithAssignee(DEFAULT_TEAM_ID, queueId, DEFAULT_ASSIGNEE_ID);
        seedSupportAgent(OTHER_ASSIGNEE_ID, "IT_SUPPORT", true);
        seedQueueMembership(OTHER_ASSIGNEE_ID, queueId);

        ResponseEntity<String> response = reassign(
            ticketId, supportToken("support-100", Set.of(DEFAULT_TEAM_ID)), "\"0\"", UUID.randomUUID().toString(),
            assignRequestBody(OTHER_ASSIGNEE_ID, DEFAULT_REASON)
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).contains("INVALID_TICKET_STATE");
        assertThat(response.getBody()).contains("\"currentStatus\":\"RESOLVED\"");
        assertThat(response.getBody()).contains("ASSIGNED");
        assertThat(response.getBody()).contains("WAITING_FOR_USER");
        assertThat(response.getBody()).contains("WAITING_FOR_APPROVAL");
    }

    @Test
    void shouldReturn409InvalidTicketStateWhenUnassigningATriagedTicket() {
        UUID queueId = seedSupportQueue(DEFAULT_TEAM_ID, true);
        UUID ticketId = seedTriagedTicketReadyToAssign(DEFAULT_TEAM_ID, queueId);

        ResponseEntity<String> response = unassign(
            ticketId, supportToken("support-100", Set.of(DEFAULT_TEAM_ID)), "\"0\"", UUID.randomUUID().toString(),
            unassignRequestBody(DEFAULT_REASON)
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).contains("INVALID_TICKET_STATE");
        assertThat(response.getBody()).contains("\"currentStatus\":\"TRIAGED\"");
        assertThat(response.getBody()).contains("\"requiredStatus\":\"ASSIGNED\"");
    }
}
