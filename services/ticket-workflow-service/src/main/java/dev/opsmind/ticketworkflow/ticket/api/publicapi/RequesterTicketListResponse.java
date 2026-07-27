package dev.opsmind.ticketworkflow.ticket.api.publicapi;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.List;

/**
 * Matches {@code schemas/requester-ticket-list-response.schema.json}
 * (SPEC-TW-003 §10).
 *
 * <p>{@code @JsonInclude(ALWAYS)} overrides the application-wide {@code
 * non_null} Jackson default: {@code page.nextCursor} and {@code
 * appliedFilters.createdFrom}/{@code createdTo} are schema-required
 * properties that are still allowed to be {@code null}, so they must be
 * serialized rather than silently dropped.
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record RequesterTicketListResponse(
    List<RequesterTicketSummaryResponse> items,
    Page page,
    AppliedFilters appliedFilters
) {

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Page(int limit, boolean hasMore, String nextCursor) {
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record AppliedFilters(List<String> status, List<String> applicationCode, Instant createdFrom, Instant createdTo) {
    }
}
