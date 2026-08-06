package dev.opsmind.ticketworkflow.ticket.application.port.out;

import java.util.UUID;

/** SPEC-TW-021: the minimal projection of an already-recorded {@code ticket_tool_execution_results} row needed to classify a {@code toolExecutionId} that has been seen before. */
public record TicketToolExecutionExistingRecord(
    UUID ticketId,
    String resultStatus
) {
}
