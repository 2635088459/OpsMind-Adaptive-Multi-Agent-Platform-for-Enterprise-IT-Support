package dev.opsmind.ticketworkflow.ticket.application.service;

import dev.opsmind.ticketworkflow.ticket.application.cursor.TicketListCursorCodec;
import dev.opsmind.ticketworkflow.ticket.application.exception.InvalidCursorException;
import dev.opsmind.ticketworkflow.ticket.application.exception.TicketAuthorizationException;
import dev.opsmind.ticketworkflow.ticket.application.observability.TicketTelemetry;
import dev.opsmind.ticketworkflow.ticket.application.port.in.ListRequesterTicketsUseCase;
import dev.opsmind.ticketworkflow.ticket.application.port.out.ClockPort;
import dev.opsmind.ticketworkflow.ticket.application.port.out.RequesterTicketQueryPort;
import dev.opsmind.ticketworkflow.ticket.application.query.ListRequesterTicketsQuery;
import dev.opsmind.ticketworkflow.ticket.application.query.RequesterTicketListResult;
import dev.opsmind.ticketworkflow.ticket.application.query.RequesterTicketSummary;
import dev.opsmind.ticketworkflow.ticket.application.query.TicketListCursor;
import dev.opsmind.ticketworkflow.ticket.application.query.TicketListFilters;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * List Requester Tickets (SPEC-TW-003). Ownership is enforced by the SQL
 * predicate in {@link RequesterTicketQueryPort}, not filtered afterward in
 * Java; the cursor is decoded and bound-checked before the query port is
 * ever called, so a tampered or cross-actor cursor never causes a lookup
 * against another requester's rows.
 */
@Service
public class ListRequesterTicketsApplicationService implements ListRequesterTicketsUseCase {

    private static final String OPERATION_ID = "listRequesterTickets";
    private static final String REQUIRED_SCOPE = "tickets:read:self";
    private static final Duration CURSOR_TTL = Duration.ofHours(24);

    private final RequesterTicketQueryPort queryPort;
    private final TicketListCursorCodec cursorCodec;
    private final TicketTelemetry telemetry;
    private final ClockPort clock;

    public ListRequesterTicketsApplicationService(
        RequesterTicketQueryPort queryPort,
        TicketListCursorCodec cursorCodec,
        TicketTelemetry telemetry,
        ClockPort clock
    ) {
        this.queryPort = queryPort;
        this.cursorCodec = cursorCodec;
        this.telemetry = telemetry;
        this.clock = clock;
    }

    @Override
    public RequesterTicketListResult list(ListRequesterTicketsQuery query) {
        var timerSample = telemetry.startListTimer();
        try {
            if (!query.actor().hasScope(REQUIRED_SCOPE)) {
                telemetry.recordAuthorizationDenied(OPERATION_ID);
                telemetry.recordListAuthorizationDenied();
                throw new TicketAuthorizationException(REQUIRED_SCOPE);
            }

            TicketListFilters filters = query.filters();
            String filterFingerprint = filters.fingerprint();
            Instant now = clock.now();

            Instant cursorLastCreatedAt = null;
            UUID cursorLastTicketId = null;
            if (query.cursor() != null && !query.cursor().isBlank()) {
                TicketListCursor cursor = decodeAndMatch(query.cursor(), filterFingerprint, query.actor().subject(), now);
                cursorLastCreatedAt = cursor.lastCreatedAt();
                cursorLastTicketId = cursor.lastTicketId();
            }

            List<RequesterTicketSummary> rows = queryPort.listForRequester(
                query.actor().subject(), filters, cursorLastCreatedAt, cursorLastTicketId, query.limit() + 1
            );

            boolean hasMore = rows.size() > query.limit();
            List<RequesterTicketSummary> items = hasMore ? rows.subList(0, query.limit()) : rows;

            String nextCursor = null;
            if (hasMore) {
                RequesterTicketSummary last = items.get(items.size() - 1);
                nextCursor = cursorCodec.encode(new TicketListCursor(
                    TicketListCursor.CURRENT_VERSION,
                    last.createdAt(),
                    last.ticketId(),
                    filterFingerprint,
                    TicketListCursor.SORT,
                    query.actor().subject(),
                    TicketListCursor.OPERATION,
                    now,
                    now.plus(CURSOR_TTL)
                ));
            }

            telemetry.recordList(query.cursor() != null, hasFilters(filters), items.size());

            return new RequesterTicketListResult(items, query.limit(), hasMore, nextCursor, filters);
        } finally {
            telemetry.stopListTimer(timerSample);
        }
    }

    private TicketListCursor decodeAndMatch(String token, String filterFingerprint, String principalSubject, Instant now) {
        try {
            TicketListCursor cursor = cursorCodec.decode(token, now);
            cursorCodec.requireMatch(cursor, filterFingerprint, principalSubject);
            return cursor;
        } catch (InvalidCursorException e) {
            telemetry.recordListInvalidCursor();
            throw e;
        }
    }

    private static boolean hasFilters(TicketListFilters filters) {
        return !filters.statuses().isEmpty()
            || !filters.applicationCodes().isEmpty()
            || filters.createdFrom() != null
            || filters.createdTo() != null;
    }
}
