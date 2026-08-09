package dev.opsmind.ticketworkflow.ticket.domain.exception;

/**
 * Raised when {@code Ticket.updateAssignment(...)} would clear {@code
 * newAssigneeId} to {@code null} while the ticket's (unchanged, SPEC-TW-030
 * is a same-lifecycle-state mutation) status is one of {@code
 * IN_PROGRESS}/{@code WAITING_FOR_USER}/{@code WAITING_FOR_APPROVAL} — the
 * same work states V015's {@code ck_tickets_work_states_have_assignee}
 * CHECK constraint requires a non-null {@code current_support_user_id}
 * for. Maps to {@code 400 VALIDATION_ERROR}: the request itself describes
 * an invalid ownership state for the ticket's current status, not a
 * conflict with concurrent state.
 */
public class AssigneeRequiredForCurrentStatusException extends RuntimeException {

    public AssigneeRequiredForCurrentStatusException() {
        super("the ticket's current status requires an assignee and cannot be routed to an unassigned state");
    }
}
