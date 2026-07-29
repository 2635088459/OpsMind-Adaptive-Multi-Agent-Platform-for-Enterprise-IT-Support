package dev.opsmind.ticketworkflow.ticket.api.publicapi;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Matches {@code schemas/employee-timeline-response.schema.json}
 * (SPEC-TW-006 §19). {@code @JsonInclude(ALWAYS)} overrides the
 * application-wide {@code non_null} Jackson default: {@code
 * page.nextCursor} is schema-required but still allowed to be {@code
 * null}, so it must be serialized rather than silently dropped.
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record EmployeeTimelineResponse(
    UUID ticketId,
    String displayId,
    String viewType,
    List<EmployeeTimelineItemResponse> items,
    Page page,
    Sort sort
) {

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Page(int limit, boolean hasMore, String nextCursor, Instant snapshotAt, String consistency) {
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Sort(int version, List<String> fields) {
    }
}
