package dev.opsmind.ticketworkflow.ticket.application.port.out;

public interface TicketToolExecutionCompletedRepository {

    /** Idempotency check: {@code toolExecutionId} is the business dedup key (unique on {@code ticket_tool_execution_results}). */
    boolean existsByToolExecutionId(String toolExecutionId);

    TicketToolExecutionCompletedUpdateOutcome applyToolExecutionCompleted(TicketToolExecutionCompletedUpdate update);
}
