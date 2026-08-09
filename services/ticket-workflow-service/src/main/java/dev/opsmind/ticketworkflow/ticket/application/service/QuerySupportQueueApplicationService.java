package dev.opsmind.ticketworkflow.ticket.application.service;

import dev.opsmind.ticketworkflow.configuration.TicketWorkflowProperties;
import dev.opsmind.ticketworkflow.ticket.application.cursor.SupportQueueCursorCodec;
import dev.opsmind.ticketworkflow.ticket.application.exception.FilterOutsideAuthorizedScopeException;
import dev.opsmind.ticketworkflow.ticket.application.exception.InvalidCursorException;
import dev.opsmind.ticketworkflow.ticket.application.exception.TicketAuthorizationException;
import dev.opsmind.ticketworkflow.ticket.application.observability.TicketTelemetry;
import dev.opsmind.ticketworkflow.ticket.application.policy.SupportQueueAuthorizationAuditRecorder;
import dev.opsmind.ticketworkflow.ticket.application.policy.SupportQueueAuthorizationDecisionCode;
import dev.opsmind.ticketworkflow.ticket.application.policy.TicketViewPolicy;
import dev.opsmind.ticketworkflow.ticket.application.port.in.QuerySupportQueueUseCase;
import dev.opsmind.ticketworkflow.ticket.application.port.out.ClockPort;
import dev.opsmind.ticketworkflow.ticket.application.port.out.SupportQueueQueryPort;
import dev.opsmind.ticketworkflow.ticket.application.query.SupportQueueCursor;
import dev.opsmind.ticketworkflow.ticket.application.query.SupportQueueFilters;
import dev.opsmind.ticketworkflow.ticket.application.query.SupportQueueKeysetPosition;
import dev.opsmind.ticketworkflow.ticket.application.query.SupportQueueQuery;
import dev.opsmind.ticketworkflow.ticket.application.query.SupportQueueResult;
import dev.opsmind.ticketworkflow.ticket.application.query.SupportQueueScope;
import dev.opsmind.ticketworkflow.ticket.application.query.SupportQueueSortVersion;
import dev.opsmind.ticketworkflow.ticket.application.query.SupportTicketSummary;
import dev.opsmind.ticketworkflow.ticket.application.query.TicketViewType;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;

/**
 * Support Queue Query (SPEC-TW-005). Resource authorization
 * (applicationCode) and every filter live in the SQL predicate behind
 * {@link SupportQueueQueryPort}, never as a post-query Java filter. The
 * cursor's {@code evaluationTime} is fixed on the first page and carried
 * unchanged through every subsequent page in the same session (§11), so
 * SLA urgency ranking is stable while real time passes between requests.
 */
@Service
public class QuerySupportQueueApplicationService implements QuerySupportQueueUseCase {

    private static final String OPERATION_ID = "supportQueue";
    private static final Duration CURSOR_TTL = Duration.ofHours(1);
    private static final Set<String> MANAGEMENT_SCOPE_ACTOR_TYPES = Set.of("IT_ADMIN", "IT_MANAGER");

    private final SupportQueueQueryPort queryPort;
    private final SupportQueueCursorCodec cursorCodec;
    private final TicketViewPolicy viewPolicy;
    private final TicketTelemetry telemetry;
    private final ClockPort clock;
    private final Duration atRiskWindow;
    private final SupportQueueAuthorizationAuditRecorder authorizationAuditRecorder;

    public QuerySupportQueueApplicationService(
        SupportQueueQueryPort queryPort,
        SupportQueueCursorCodec cursorCodec,
        TicketViewPolicy viewPolicy,
        TicketTelemetry telemetry,
        ClockPort clock,
        TicketWorkflowProperties properties,
        SupportQueueAuthorizationAuditRecorder authorizationAuditRecorder
    ) {
        this.queryPort = queryPort;
        this.cursorCodec = cursorCodec;
        this.viewPolicy = viewPolicy;
        this.telemetry = telemetry;
        this.clock = clock;
        this.atRiskWindow = properties.sla().atRiskWindow();
        this.authorizationAuditRecorder = authorizationAuditRecorder;
    }

    @Override
    public SupportQueueResult query(SupportQueueQuery query) {
        var timerSample = telemetry.startSupportQueueTimer();
        try {
            TicketViewType viewType = viewPolicy.resolve(query.actor());
            if (viewType != TicketViewType.SUPPORT_VIEW) {
                telemetry.recordAuthorizationDenied(OPERATION_ID);
                telemetry.recordSupportQueueAuthorizationDenied();
                throw new TicketAuthorizationException(viewPolicy.requiredScope(TicketViewType.SUPPORT_VIEW));
            }
            String requiredScope = viewPolicy.requiredScope(viewType);
            if (!query.actor().hasScope(requiredScope)) {
                telemetry.recordAuthorizationDenied(OPERATION_ID);
                telemetry.recordSupportQueueAuthorizationDenied();
                throw new TicketAuthorizationException(requiredScope);
            }

            requireFiltersWithinScope(query);

            SupportQueueFilters filters = query.filters();
            String filterFingerprint = filters.fingerprint();
            String scopeFingerprint = query.scope().fingerprint();
            Instant now = clock.now();

            Instant evaluationTime = now;
            SupportQueueKeysetPosition cursorPosition = null;
            if (query.cursor() != null && !query.cursor().isBlank()) {
                SupportQueueCursor cursor = decodeAndMatch(query.cursor(), filterFingerprint, scopeFingerprint, query.actor().subject(), now);
                evaluationTime = cursor.evaluationTime();
                cursorPosition = new SupportQueueKeysetPosition(
                    cursor.lastSlaRank(), cursor.lastPriorityRank(), cursor.lastCreatedAt(), cursor.lastTicketId()
                );
            }

            List<SupportTicketSummary> rows = queryPort.queryQueue(
                query.scope(), filters, evaluationTime, atRiskWindow, cursorPosition, query.limit() + 1
            );

            boolean hasMore = rows.size() > query.limit();
            List<SupportTicketSummary> items = hasMore ? rows.subList(0, query.limit()) : rows;

            String nextCursor = null;
            if (hasMore) {
                SupportTicketSummary last = items.get(items.size() - 1);
                nextCursor = cursorCodec.encode(new SupportQueueCursor(
                    SupportQueueCursor.CURRENT_VERSION,
                    evaluationTime,
                    last.slaState().urgencyRank(),
                    last.priority().priorityRank(),
                    last.createdAt(),
                    last.ticketId(),
                    filterFingerprint,
                    scopeFingerprint,
                    query.actor().subject(),
                    SupportQueueSortVersion.CURRENT_VERSION,
                    SupportQueueCursor.OPERATION,
                    now,
                    now.plus(CURSOR_TTL)
                ));
            }

            telemetry.recordSupportQueueList(query.cursor() != null, items.size());

            return new SupportQueueResult(items, query.limit(), hasMore, nextCursor, evaluationTime, filters);
        } finally {
            telemetry.stopSupportQueueTimer(timerSample);
        }
    }

    private void requireFiltersWithinScope(SupportQueueQuery query) {
        SupportQueueScope scope = query.scope();
        SupportQueueFilters filters = query.filters();

        if (!filters.applicationCodes().isEmpty() && !scope.allowedApplicationCodes().containsAll(filters.applicationCodes())) {
            telemetry.recordSupportQueueFilterOutsideScope();
            throw denyFilterOutsideScope(query);
        }
        if (!filters.assignedTeams().isEmpty() && !scope.allowedTeamIds().containsAll(filters.assignedTeams())) {
            telemetry.recordSupportQueueFilterOutsideScope();
            throw denyFilterOutsideScope(query);
        }
        if (filters.assignedAgent() != null && !filters.assignedAgent().isBlank()
            && !filters.assignedAgent().equals(query.actor().subject())
            && !MANAGEMENT_SCOPE_ACTOR_TYPES.contains(query.actor().actorType())) {
            telemetry.recordSupportQueueFilterOutsideScope();
            throw denyFilterOutsideScope(query);
        }
    }

    /** SPEC-TW-033 hardening: hooks Support Queue List's existing filter-scope rejection into the shared decision ledger/metrics, without changing which requests are allowed or denied. */
    private FilterOutsideAuthorizedScopeException denyFilterOutsideScope(SupportQueueQuery query) {
        authorizationAuditRecorder.recordDenied(
            null, query.actor().subject(), query.actor().actorType(), "ticket.query",
            SupportQueueAuthorizationDecisionCode.DENIED_SCOPE, null, currentTraceId()
        );
        return new FilterOutsideAuthorizedScopeException();
    }

    private String currentTraceId() {
        String traceId = MDC.get("traceId");
        return traceId == null ? "" : traceId;
    }

    private SupportQueueCursor decodeAndMatch(
        String token, String filterFingerprint, String scopeFingerprint, String principalSubject, Instant now
    ) {
        try {
            SupportQueueCursor cursor = cursorCodec.decode(token, now);
            cursorCodec.requireMatch(cursor, filterFingerprint, scopeFingerprint, principalSubject);
            return cursor;
        } catch (InvalidCursorException e) {
            telemetry.recordSupportQueueInvalidCursor();
            throw e;
        }
    }
}
