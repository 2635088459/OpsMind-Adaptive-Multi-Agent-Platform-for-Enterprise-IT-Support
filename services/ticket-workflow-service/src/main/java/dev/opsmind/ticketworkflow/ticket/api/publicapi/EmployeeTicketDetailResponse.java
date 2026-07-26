package dev.opsmind.ticketworkflow.ticket.api.publicapi;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.UUID;

/**
 * Matches {@code schemas/employee-ticket-response.schema.json}
 * (SPEC-TW-002 §12). Never includes requesterRef, internal assignment IDs,
 * workflow metadata, or audit metadata.
 *
 * <p>{@code @JsonInclude(ALWAYS)} overrides the application-wide {@code
 * non_null} Jackson default: {@code sla.responseDueAt} / {@code
 * resolutionDueAt} are schema-required properties that are still allowed to
 * be {@code null}, so they must be serialized rather than silently dropped.
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record EmployeeTicketDetailResponse(
    UUID ticketId,
    String displayId,
    String title,
    String description,
    String applicationCode,
    String source,
    String status,
    String priority,
    Instant createdAt,
    Instant updatedAt,
    long version,
    Sla sla,
    Links links
) {

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Sla(String state, Instant responseDueAt, Instant resolutionDueAt) {
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Links(String self, String timeline, String messages) {
    }
}
