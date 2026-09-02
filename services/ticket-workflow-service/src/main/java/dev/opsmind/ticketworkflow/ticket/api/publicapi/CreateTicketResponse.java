package dev.opsmind.ticketworkflow.ticket.api.publicapi;

import java.time.Instant;
import java.util.UUID;

/**
 * resolutionCycleId: added so a synchronous machine caller (agent-runtime-service's
 * SPEC-ARO-038, which must bind a new WorkflowInstance to ticketId + ticketCycleId
 * within this same request) can read the initial resolution cycle id without waiting
 * on the async ticket.created.v1 event that would otherwise be the only place it
 * appears. Every other public/support read surface (EmployeeTicketDetailResponse,
 * SupportTicketDetailResponse) still deliberately omits the raw cycle id — this
 * endpoint is the one place a caller needs it at the moment of creation.
 */
public record CreateTicketResponse(
    UUID ticketId,
    String displayId,
    String status,
    Instant createdAt,
    long version,
    UUID resolutionCycleId
) {
}
