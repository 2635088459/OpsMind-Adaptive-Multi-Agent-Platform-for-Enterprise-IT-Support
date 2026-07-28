package dev.opsmind.ticketworkflow.ticket.api.support;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.UUID;

/**
 * Matches {@code schemas/support-ticket-summary.schema.json} (SPEC-TW-005
 * §17). Never includes full Description, message/note content, requester
 * email, or any other field forbidden by §17.
 *
 * <p>{@code @JsonInclude(ALWAYS)}: {@code assignment.teamId}/{@code
 * agentId} and the SLA due-date fields are schema-required but still
 * allowed to be {@code null}, so they must be serialized rather than
 * silently dropped.
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record SupportTicketSummaryResponse(
    UUID ticketId,
    String displayId,
    String title,
    String applicationCode,
    String status,
    String priority,
    String requesterRef,
    Assignment assignment,
    Sla sla,
    Instant createdAt,
    Instant updatedAt,
    long version
) {

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Assignment(String teamId, String agentId, boolean unassigned) {
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Sla(String state, Instant responseDueAt, Instant resolutionDueAt, int urgencyRank) {
    }
}
