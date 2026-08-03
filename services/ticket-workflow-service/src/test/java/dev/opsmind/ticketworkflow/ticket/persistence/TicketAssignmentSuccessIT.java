package dev.opsmind.ticketworkflow.ticket.persistence;

import dev.opsmind.ticketworkflow.support.AbstractTicketAssignmentIT;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SPEC-TW-008 AC-01/AC-02/AC-03: the full happy path for assign, reassign,
 * and unassign via real HTTP, asserting every persisted field and the
 * atomic side-writes (ticket row, assignment history, status history where
 * applicable, outbox).
 */
@Tag("integration")
class TicketAssignmentSuccessIT extends AbstractTicketAssignmentIT {

    @Test
    void shouldAssignATriagedTicketAndPersistEveryExpectedField() {
        UUID queueId = seedSupportQueue(DEFAULT_TEAM_ID, true);
        UUID ticketId = seedTriagedTicketReadyToAssign(DEFAULT_TEAM_ID, queueId);
        seedSupportAgent(DEFAULT_ASSIGNEE_ID, "IT_SUPPORT", true);
        seedQueueMembership(DEFAULT_ASSIGNEE_ID, queueId);

        ResponseEntity<String> response = assign(
            ticketId, supportToken("support-100", Set.of(DEFAULT_TEAM_ID)), "\"0\"", UUID.randomUUID().toString(),
            assignRequestBody(DEFAULT_ASSIGNEE_ID, DEFAULT_REASON)
        );

        assertThat(response.getStatusCode()).as(response.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getETag()).isEqualTo("\"1\"");
        assertThat(response.getBody()).contains("\"id\":\"" + DEFAULT_ASSIGNEE_ID + "\"");
        assertThat(response.getBody()).contains("\"displayName\":\"Display " + DEFAULT_ASSIGNEE_ID + "\"");

        Map<String, Object> row = ticketRow(ticketId);
        assertThat(row.get("status")).isEqualTo("ASSIGNED");
        assertThat(row.get("current_support_user_id")).isEqualTo(DEFAULT_ASSIGNEE_ID);
        assertThat(row.get("assigned_at")).isNotNull();
        assertThat(row.get("assigned_by")).isEqualTo("support-100");
        assertThat(row.get("version")).isEqualTo(1L);

        Map<String, Object> history = assignmentHistoryRow(ticketId);
        assertThat(history.get("action")).isEqualTo("ASSIGNED");
        assertThat(history.get("previous_assignee_id")).isNull();
        assertThat(history.get("new_assignee_id")).isEqualTo(DEFAULT_ASSIGNEE_ID);
        assertThat(history.get("previous_status")).isEqualTo("TRIAGED");
        assertThat(history.get("new_status")).isEqualTo("ASSIGNED");
        assertThat(history.get("resulting_version")).isEqualTo(1L);
        assertThat(countRows("ticket.ticket_assignment_history")).isEqualTo(1);

        Map<String, Object> statusHistory = jdbcTemplate.queryForMap(
            "SELECT * FROM ticket.ticket_status_history WHERE ticket_id = ?", ticketId
        );
        assertThat(statusHistory.get("from_status")).isEqualTo("TRIAGED");
        assertThat(statusHistory.get("to_status")).isEqualTo("ASSIGNED");
        assertThat(statusHistory.get("transition_id")).isEqualTo("SM-003");
        assertThat(statusHistory.get("reason_code")).isEqualTo("TICKET_ASSIGNED");
        assertThat(countRows("ticket.ticket_status_history")).isEqualTo(1);

        assertThat(countRows("ticket.audit_records")).isEqualTo(1);

        Map<String, Object> outbox = jdbcTemplate.queryForMap("SELECT * FROM ticket.outbox_events WHERE ticket_id = ?", ticketId);
        assertThat(outbox.get("event_type")).isEqualTo("ticket.assigned");
        assertThat(outbox.get("event_version")).isEqualTo("1.0");
        assertThat(outbox.get("routing_key")).isEqualTo("ticket.assigned.v1");
        assertThat(outbox.get("published_at")).isNull();
        assertThat(countRows("ticket.outbox_events")).isEqualTo(1);
    }

    @Test
    void shouldReassignAnAssignedTicketPreservingStatusAndSkippingStatusHistory() {
        UUID queueId = seedSupportQueue(DEFAULT_TEAM_ID, true);
        UUID ticketId = seedAssignedTicket(DEFAULT_TEAM_ID, queueId, DEFAULT_ASSIGNEE_ID);
        seedSupportAgent(OTHER_ASSIGNEE_ID, "IT_SUPPORT", true);
        seedQueueMembership(OTHER_ASSIGNEE_ID, queueId);

        ResponseEntity<String> response = reassign(
            ticketId, supportToken("support-100", Set.of(DEFAULT_TEAM_ID)), "\"0\"", UUID.randomUUID().toString(),
            assignRequestBody(OTHER_ASSIGNEE_ID, "Escalated to network specialist")
        );

        assertThat(response.getStatusCode()).as(response.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getETag()).isEqualTo("\"1\"");

        Map<String, Object> row = ticketRow(ticketId);
        assertThat(row.get("status")).isEqualTo("ASSIGNED");
        assertThat(row.get("current_support_user_id")).isEqualTo(OTHER_ASSIGNEE_ID);
        assertThat(row.get("version")).isEqualTo(1L);

        Map<String, Object> history = assignmentHistoryRow(ticketId);
        assertThat(history.get("action")).isEqualTo("REASSIGNED");
        assertThat(history.get("previous_assignee_id")).isEqualTo(DEFAULT_ASSIGNEE_ID);
        assertThat(history.get("new_assignee_id")).isEqualTo(OTHER_ASSIGNEE_ID);
        assertThat(history.get("previous_status")).isEqualTo("ASSIGNED");
        assertThat(history.get("new_status")).isEqualTo("ASSIGNED");
        assertThat(countRows("ticket.ticket_assignment_history")).isEqualTo(1);

        // persistence §3: reassignment does not add a status-history row (status is unchanged).
        assertThat(countRows("ticket.ticket_status_history")).isZero();

        Map<String, Object> outbox = jdbcTemplate.queryForMap("SELECT * FROM ticket.outbox_events WHERE ticket_id = ?", ticketId);
        assertThat(outbox.get("event_type")).isEqualTo("ticket.reassigned");
        assertThat(outbox.get("routing_key")).isEqualTo("ticket.reassigned.v1");
        assertThat(countRows("ticket.outbox_events")).isEqualTo(1);
    }

    @Test
    void shouldUnassignAnAssignedTicketClearingOwnershipAndReturningToTriaged() {
        UUID queueId = seedSupportQueue(DEFAULT_TEAM_ID, true);
        UUID ticketId = seedAssignedTicket(DEFAULT_TEAM_ID, queueId, DEFAULT_ASSIGNEE_ID);

        ResponseEntity<String> response = unassign(
            ticketId, supportToken("support-100", Set.of(DEFAULT_TEAM_ID)), "\"0\"", UUID.randomUUID().toString(),
            unassignRequestBody("Agent left the support rotation")
        );

        assertThat(response.getStatusCode()).as(response.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getETag()).isEqualTo("\"1\"");

        Map<String, Object> row = ticketRow(ticketId);
        assertThat(row.get("status")).isEqualTo("TRIAGED");
        assertThat(row.get("current_support_user_id")).isNull();
        assertThat(row.get("assigned_at")).isNull();
        assertThat(row.get("assigned_by")).isNull();
        assertThat(row.get("version")).isEqualTo(1L);

        Map<String, Object> history = assignmentHistoryRow(ticketId);
        assertThat(history.get("action")).isEqualTo("UNASSIGNED");
        assertThat(history.get("previous_assignee_id")).isEqualTo(DEFAULT_ASSIGNEE_ID);
        assertThat(history.get("new_assignee_id")).isNull();
        assertThat(history.get("previous_status")).isEqualTo("ASSIGNED");
        assertThat(history.get("new_status")).isEqualTo("TRIAGED");
        assertThat(countRows("ticket.ticket_assignment_history")).isEqualTo(1);

        Map<String, Object> statusHistory = jdbcTemplate.queryForMap(
            "SELECT * FROM ticket.ticket_status_history WHERE ticket_id = ?", ticketId
        );
        assertThat(statusHistory.get("from_status")).isEqualTo("ASSIGNED");
        assertThat(statusHistory.get("to_status")).isEqualTo("TRIAGED");
        assertThat(statusHistory.get("transition_id")).isEqualTo("SM-004");
        assertThat(statusHistory.get("reason_code")).isEqualTo("TICKET_UNASSIGNED");
        assertThat(countRows("ticket.ticket_status_history")).isEqualTo(1);

        Map<String, Object> outbox = jdbcTemplate.queryForMap("SELECT * FROM ticket.outbox_events WHERE ticket_id = ?", ticketId);
        assertThat(outbox.get("event_type")).isEqualTo("ticket.unassigned");
        assertThat(outbox.get("routing_key")).isEqualTo("ticket.unassigned.v1");
        assertThat(countRows("ticket.outbox_events")).isEqualTo(1);
    }

    /**
     * SPEC-TW-008 deviation #12 consequence: since Reassign never writes a
     * status-history row and SPEC-TW-006's Timeline unions {@code tickets}/
     * {@code ticket_status_history}/{@code ticket_messages} only (not {@code
     * ticket_assignment_history}), a Reassign leaves zero new Timeline-
     * relevant rows. This is the direct, spec-required consequence rather
     * than a bug — asserted here via the status-history row count already
     * checked above rather than a live Timeline HTTP call (see report).
     */
    @Test
    void reassignShouldLeaveTheStatusHistoryTableUntouchedConfirmingTheTimelineGap() {
        UUID queueId = seedSupportQueue(DEFAULT_TEAM_ID, true);
        UUID ticketId = seedAssignedTicket(DEFAULT_TEAM_ID, queueId, DEFAULT_ASSIGNEE_ID);
        seedSupportAgent(OTHER_ASSIGNEE_ID, "IT_SUPPORT", true);
        seedQueueMembership(OTHER_ASSIGNEE_ID, queueId);
        assertThat(countRows("ticket.ticket_status_history")).isZero();

        ResponseEntity<String> response = reassign(
            ticketId, supportToken("support-100", Set.of(DEFAULT_TEAM_ID)), "\"0\"", UUID.randomUUID().toString(),
            assignRequestBody(OTHER_ASSIGNEE_ID, "Escalated to network specialist")
        );

        assertThat(response.getStatusCode()).as(response.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(countRows("ticket.ticket_status_history")).isZero();
    }
}
