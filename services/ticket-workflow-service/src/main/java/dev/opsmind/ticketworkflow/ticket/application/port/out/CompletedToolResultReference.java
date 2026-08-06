package dev.opsmind.ticketworkflow.ticket.application.port.out;

/** SPEC-TW-022: the minimal projection of a Phase 06 {@code COMPLETED} tool result (from {@code ticket_tool_execution_results}) needed to start a verification attempt against it. */
public record CompletedToolResultReference(
    String workflowId
) {
}
