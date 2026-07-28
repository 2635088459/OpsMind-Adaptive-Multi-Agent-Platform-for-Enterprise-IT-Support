package dev.opsmind.ticketworkflow.ticket.api.support;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.List;

/**
 * Matches {@code schemas/support-queue-response.schema.json} (SPEC-TW-005
 * §16). {@code @JsonInclude(ALWAYS)} overrides the application-wide {@code
 * non_null} Jackson default: {@code page.nextCursor} and several {@code
 * appliedFilters} fields are schema-required but still allowed to be
 * {@code null}, so they must be serialized rather than silently dropped.
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record SupportQueueResponse(
    List<SupportTicketSummaryResponse> items,
    Page page,
    Sort sort,
    AppliedFilters appliedFilters
) {

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Page(int limit, boolean hasMore, String nextCursor, Instant evaluationTime, String consistency) {
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Sort(int version, List<String> fields) {
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record AppliedFilters(
        List<String> status,
        List<String> priority,
        List<String> applicationCode,
        List<String> assignedTeam,
        String assignedAgent,
        boolean unassignedOnly,
        List<String> slaState,
        Instant createdFrom,
        Instant createdTo
    ) {
    }
}
