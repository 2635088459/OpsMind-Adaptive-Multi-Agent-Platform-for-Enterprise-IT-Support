package dev.opsmind.ticketworkflow.ticket.api.support;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Matches {@code schemas/support-timeline-response.schema.json} (SPEC-TW-006 §20). */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record SupportTimelineResponse(
    UUID ticketId,
    String displayId,
    String viewType,
    List<SupportTimelineItemResponse> items,
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
