package dev.opsmind.ticketworkflow.ticket.api.support;

import dev.opsmind.ticketworkflow.ticket.api.exception.RequestValidationException;
import dev.opsmind.ticketworkflow.ticket.application.event.RequesterPseudonymizer;
import dev.opsmind.ticketworkflow.ticket.application.query.SlaQueueState;
import dev.opsmind.ticketworkflow.ticket.application.query.SupportQueueFilters;
import dev.opsmind.ticketworkflow.ticket.application.query.SupportQueuePriority;
import dev.opsmind.ticketworkflow.ticket.application.query.SupportQueueResult;
import dev.opsmind.ticketworkflow.ticket.application.query.SupportQueueSortVersion;
import dev.opsmind.ticketworkflow.ticket.application.query.SupportTicketSummary;
import dev.opsmind.ticketworkflow.ticket.domain.value.ApplicationCode;
import dev.opsmind.ticketworkflow.ticket.domain.value.RequesterId;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketStatus;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * Maps between the Support Queue's wire contract and its application-layer
 * query types (SPEC-TW-005 §16, §17). {@code requesterRef} is pseudonymized
 * here, at the response boundary, using the same {@link
 * RequesterPseudonymizer} as Get Ticket's Support view (SPEC-TW-002), so
 * the same requester maps to the same reference on both endpoints.
 */
@Component
public class SupportQueueApiMapper {

    private final RequesterPseudonymizer requesterPseudonymizer;

    public SupportQueueApiMapper(RequesterPseudonymizer requesterPseudonymizer) {
        this.requesterPseudonymizer = requesterPseudonymizer;
    }

    public SupportQueueFilters toFilters(
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
        return new SupportQueueFilters(
            parseEnumValues(status, TicketStatus.class, "status"),
            parseEnumValues(priority, SupportQueuePriority.class, "priority"),
            parseEnumValues(applicationCode, ApplicationCode.class, "applicationCode"),
            assignedTeam == null ? Set.of() : Set.copyOf(assignedTeam),
            (assignedAgent == null || assignedAgent.isBlank()) ? null : assignedAgent,
            unassignedOnly,
            parseEnumValues(slaState, SlaQueueState.class, "slaState"),
            createdFrom,
            createdTo
        );
    }

    public SupportQueueResponse toResponse(SupportQueueResult result) {
        List<SupportTicketSummaryResponse> items = result.items().stream().map(this::toSummary).toList();

        SupportQueueResponse.Page page = new SupportQueueResponse.Page(
            result.limit(), result.hasMore(), result.nextCursor(), result.evaluationTime(), "LIVE"
        );

        SupportQueueResponse.Sort sort = new SupportQueueResponse.Sort(
            SupportQueueSortVersion.CURRENT_VERSION, SupportQueueSortVersion.FIELDS
        );

        SupportQueueFilters filters = result.appliedFilters();
        SupportQueueResponse.AppliedFilters appliedFilters = new SupportQueueResponse.AppliedFilters(
            sortedNames(filters.statuses()),
            sortedNames(filters.priorities()),
            sortedNames(filters.applicationCodes()),
            filters.assignedTeams().stream().sorted().toList(),
            filters.assignedAgent(),
            filters.unassignedOnly(),
            sortedNames(filters.slaStates()),
            filters.createdFrom(),
            filters.createdTo()
        );

        return new SupportQueueResponse(items, page, sort, appliedFilters);
    }

    private SupportTicketSummaryResponse toSummary(SupportTicketSummary summary) {
        return new SupportTicketSummaryResponse(
            summary.ticketId(),
            summary.displayId(),
            summary.title(),
            summary.applicationCode(),
            summary.status(),
            summary.priority().name(),
            requesterPseudonymizer.pseudonymize(RequesterId.of(summary.requesterId())),
            new SupportTicketSummaryResponse.Assignment(summary.teamId(), summary.agentId(), summary.unassigned()),
            new SupportTicketSummaryResponse.Sla(
                summary.slaState().name(), summary.slaResponseDueAt(), summary.slaResolutionDueAt(), summary.slaUrgencyRank()
            ),
            summary.createdAt(),
            summary.updatedAt(),
            summary.version()
        );
    }

    private static <E extends Enum<E>> Set<E> parseEnumValues(List<String> raw, Class<E> enumType, String fieldName) {
        if (raw == null || raw.isEmpty()) {
            return Set.of();
        }
        EnumSet<E> values = EnumSet.noneOf(enumType);
        for (String value : raw) {
            try {
                values.add(Enum.valueOf(enumType, value));
            } catch (IllegalArgumentException e) {
                throw new RequestValidationException(fieldName + " contains an invalid value: " + value);
            }
        }
        return values;
    }

    private static List<String> sortedNames(Set<? extends Enum<?>> values) {
        return values.stream().map(Enum::name).sorted().toList();
    }
}
