package dev.opsmind.ticketworkflow.ticket.api.publicapi;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;

/**
 * Matches {@code schemas/employee-timeline-item.schema.json} (SPEC-TW-006
 * §19). Never includes actor IDs, internal actor references, internal
 * notes, internal reason codes, workflow IDs, or Audit metadata.
 *
 * <p>{@code @JsonInclude(ALWAYS)}: {@code content} and every {@code
 * metadata} field are schema-required but still allowed to be {@code
 * null}, so they must be serialized rather than silently dropped.
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record EmployeeTimelineItemResponse(
    String itemId,
    String itemType,
    String visibility,
    Instant occurredAt,
    Actor actor,
    String summary,
    String content,
    Metadata metadata
) {

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Actor(String type, String displayLabel) {
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Metadata(String fromStatus, String toStatus, String messageType, Long relatedVersion) {
    }
}
