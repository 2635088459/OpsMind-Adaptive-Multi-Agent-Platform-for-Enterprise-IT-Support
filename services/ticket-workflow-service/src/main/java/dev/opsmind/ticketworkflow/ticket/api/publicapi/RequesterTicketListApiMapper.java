package dev.opsmind.ticketworkflow.ticket.api.publicapi;

import dev.opsmind.ticketworkflow.ticket.api.exception.RequestValidationException;
import dev.opsmind.ticketworkflow.ticket.application.query.RequesterTicketListResult;
import dev.opsmind.ticketworkflow.ticket.application.query.RequesterTicketSummary;
import dev.opsmind.ticketworkflow.ticket.application.query.TicketListFilters;
import dev.opsmind.ticketworkflow.ticket.domain.value.ApplicationCode;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketStatus;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

@Component
public class RequesterTicketListApiMapper {

    public TicketListFilters toFilters(List<String> status, List<String> applicationCode, Instant createdFrom, Instant createdTo) {
        return new TicketListFilters(
            parseEnumValues(status, TicketStatus.class, "status"),
            parseEnumValues(applicationCode, ApplicationCode.class, "applicationCode"),
            createdFrom,
            createdTo
        );
    }

    public RequesterTicketListResponse toResponse(RequesterTicketListResult result) {
        List<RequesterTicketSummaryResponse> items = result.items().stream().map(this::toSummary).toList();

        RequesterTicketListResponse.Page page = new RequesterTicketListResponse.Page(
            result.limit(), result.hasMore(), result.nextCursor()
        );

        RequesterTicketListResponse.AppliedFilters appliedFilters = new RequesterTicketListResponse.AppliedFilters(
            sortedNames(result.appliedFilters().statuses()),
            sortedNames(result.appliedFilters().applicationCodes()),
            result.appliedFilters().createdFrom(),
            result.appliedFilters().createdTo()
        );

        return new RequesterTicketListResponse(items, page, appliedFilters);
    }

    private RequesterTicketSummaryResponse toSummary(RequesterTicketSummary summary) {
        return new RequesterTicketSummaryResponse(
            summary.ticketId(),
            summary.displayId(),
            summary.title(),
            summary.applicationCode(),
            summary.status(),
            summary.priority(),
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
