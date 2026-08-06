package dev.opsmind.ticketworkflow.ticket.application.port.out;

public interface TicketToolExecutionFailedRepository {

    /** Idempotency check: {@code toolExecutionId} is the business dedup key, shared across SPEC-TW-019..021's outcomes on {@code ticket_tool_execution_results}. */
    boolean existsByToolExecutionId(String toolExecutionId);

    TicketToolExecutionFailedUpdateOutcome applyToolExecutionFailed(TicketToolExecutionFailedUpdate update);
}
