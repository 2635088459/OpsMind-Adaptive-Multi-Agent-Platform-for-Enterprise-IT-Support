package dev.opsmind.ticketworkflow.ticket.api.support;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.UUID;

/**
 * Matches {@code schemas/support-ticket-response.schema.json}
 * (SPEC-TW-002 §13). {@code requesterRef} is a pseudonymized reference, not
 * the raw internal requester identifier. Never includes secrets, tokens, or
 * full audit records.
 *
 * <p>{@code @JsonInclude(ALWAYS)} overrides the application-wide {@code
 * non_null} Jackson default: {@code assignment.teamId} / {@code agentId}
 * and the SLA due-date fields are schema-required properties that are still
 * allowed to be {@code null} (e.g. an unassigned Ticket), so they must be
 * serialized rather than silently dropped.
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record SupportTicketDetailResponse(
    UUID ticketId,
    String displayId,
    String title,
    String description,
    String applicationCode,
    String source,
    String status,
    String priority,
    String requesterRef,
    Assignment assignment,
    ResolutionCycle resolutionCycle,
    Sla sla,
    Instant createdAt,
    Instant updatedAt,
    long version
) {

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Assignment(String teamId, String agentId, String queue) {
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record ResolutionCycle(int cycleNumber, String status) {
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Sla(String state, String policyId, Instant responseDueAt, Instant resolutionDueAt) {
    }
}
