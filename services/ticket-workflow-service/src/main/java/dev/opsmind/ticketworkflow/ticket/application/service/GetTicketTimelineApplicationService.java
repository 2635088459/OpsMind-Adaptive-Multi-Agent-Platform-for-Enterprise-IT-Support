package dev.opsmind.ticketworkflow.ticket.application.service;

import dev.opsmind.ticketworkflow.ticket.application.cursor.TicketTimelineCursorCodec;
import dev.opsmind.ticketworkflow.ticket.application.exception.InvalidCursorException;
import dev.opsmind.ticketworkflow.ticket.application.exception.SensitiveReadAuditFailureException;
import dev.opsmind.ticketworkflow.ticket.application.exception.TicketAuthorizationException;
import dev.opsmind.ticketworkflow.ticket.application.exception.TicketNotFoundException;
import dev.opsmind.ticketworkflow.ticket.application.model.SensitiveReadAuditEntry;
import dev.opsmind.ticketworkflow.ticket.application.observability.TicketTelemetry;
import dev.opsmind.ticketworkflow.ticket.application.policy.TicketTimelineViewPolicy;
import dev.opsmind.ticketworkflow.ticket.application.port.in.GetTicketTimelineUseCase;
import dev.opsmind.ticketworkflow.ticket.application.port.out.ClockPort;
import dev.opsmind.ticketworkflow.ticket.application.port.out.SensitiveReadAuditPort;
import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketTimelineQueryPort;
import dev.opsmind.ticketworkflow.ticket.application.query.TicketTimelineCursor;
import dev.opsmind.ticketworkflow.ticket.application.query.TicketTimelineGuard;
import dev.opsmind.ticketworkflow.ticket.application.query.TicketTimelineItem;
import dev.opsmind.ticketworkflow.ticket.application.query.TicketTimelineKeysetPosition;
import dev.opsmind.ticketworkflow.ticket.application.query.TicketTimelineQuery;
import dev.opsmind.ticketworkflow.ticket.application.query.TicketTimelineResult;
import dev.opsmind.ticketworkflow.ticket.application.query.TicketTimelineSortVersion;
import dev.opsmind.ticketworkflow.ticket.application.query.TicketTimelineViewType;
import dev.opsmind.ticketworkflow.ticket.domain.value.ApplicationCode;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Ticket Timeline (SPEC-TW-006). Resource authorization is resolved once
 * via {@link TicketTimelineQueryPort#loadGuard} before any item is read
 * (§6); the cursor's {@code snapshotAt} is fixed on the first page and
 * carried unchanged through every later page in the same session (§13).
 * A Support-internal read always creates a required sensitive-read Audit
 * record; a failure there fails the whole read closed (§23).
 */
@Service
public class GetTicketTimelineApplicationService implements GetTicketTimelineUseCase {

    private static final String OPERATION_ID = "ticketTimeline";
    private static final String FIELDS_POLICY_VERSION = "1";
    private static final Duration CURSOR_TTL = Duration.ofHours(24);

    private final TicketTimelineQueryPort queryPort;
    private final TicketTimelineCursorCodec cursorCodec;
    private final TicketTimelineViewPolicy viewPolicy;
    private final SensitiveReadAuditPort sensitiveReadAuditPort;
    private final TicketTelemetry telemetry;
    private final ClockPort clock;

    public GetTicketTimelineApplicationService(
        TicketTimelineQueryPort queryPort,
        TicketTimelineCursorCodec cursorCodec,
        TicketTimelineViewPolicy viewPolicy,
        SensitiveReadAuditPort sensitiveReadAuditPort,
        TicketTelemetry telemetry,
        ClockPort clock
    ) {
        this.queryPort = queryPort;
        this.cursorCodec = cursorCodec;
        this.viewPolicy = viewPolicy;
        this.sensitiveReadAuditPort = sensitiveReadAuditPort;
        this.telemetry = telemetry;
        this.clock = clock;
    }

    @Override
    public TicketTimelineResult getTimeline(TicketTimelineQuery query) {
        var timerSample = telemetry.startTimelineTimer();
        try {
            TicketTimelineViewType viewType = viewPolicy.resolve(query.actor());
            String requiredScope = viewPolicy.requiredScope(viewType);

            if (viewType == TicketTimelineViewType.AUDITOR_POLICY_VIEW || !query.actor().hasScope(requiredScope)) {
                telemetry.recordTimelineAuthorizationDenied();
                throw new TicketAuthorizationException(requiredScope);
            }

            Optional<TicketTimelineGuard> guard = queryPort.loadGuard(query.ticketId());
            if (guard.isEmpty() || !isAuthorizedForResource(viewType, query, guard.get())) {
                telemetry.recordTimelineNotFound();
                throw new TicketNotFoundException();
            }

            String scopeFingerprint = scopeFingerprint(query.allowedApplicationCodes());
            Instant now = clock.now();

            Instant snapshotAt = now;
            TicketTimelineKeysetPosition cursorPosition = null;
            if (query.cursor() != null && !query.cursor().isBlank()) {
                TicketTimelineCursor cursor = decodeAndMatch(query.cursor(), query.ticketId().value().toString(), scopeFingerprint, viewType.name(), query.actor().subject(), now);
                snapshotAt = cursor.snapshotAt();
                cursorPosition = new TicketTimelineKeysetPosition(cursor.lastOccurredAt(), cursor.lastItemTypeRank(), cursor.lastItemId());
            }

            boolean includeInternal = viewType == TicketTimelineViewType.SUPPORT_INTERNAL_VIEW;

            List<TicketTimelineItem> rows = queryPort.queryTimeline(query.ticketId(), includeInternal, snapshotAt, cursorPosition, query.limit() + 1);

            boolean hasMore = rows.size() > query.limit();
            List<TicketTimelineItem> items = hasMore ? rows.subList(0, query.limit()) : rows;

            String nextCursor = null;
            if (hasMore) {
                TicketTimelineItem last = items.get(items.size() - 1);
                nextCursor = cursorCodec.encode(new TicketTimelineCursor(
                    TicketTimelineCursor.CURRENT_VERSION,
                    query.ticketId().value().toString(),
                    query.actor().subject(),
                    scopeFingerprint,
                    viewType.name(),
                    TicketTimelineCursor.CURRENT_VISIBILITY_POLICY_VERSION,
                    snapshotAt,
                    last.occurredAt(),
                    last.itemType().itemTypeRank(),
                    last.itemId(),
                    TicketTimelineSortVersion.CURRENT_VERSION,
                    TicketTimelineCursor.OPERATION,
                    now,
                    now.plus(CURSOR_TTL)
                ));
            }

            if (includeInternal) {
                auditSensitiveRead(query, viewType, now);
            }

            telemetry.recordTimeline(viewType, query.cursor() != null, items.size());

            return new TicketTimelineResult(
                query.ticketId().value(), guard.get().displayId(), viewType, items, query.limit(), hasMore, nextCursor, snapshotAt
            );
        } finally {
            telemetry.stopTimelineTimer(timerSample);
        }
    }

    private boolean isAuthorizedForResource(TicketTimelineViewType viewType, TicketTimelineQuery query, TicketTimelineGuard guard) {
        return switch (viewType) {
            case EMPLOYEE_PUBLIC_VIEW -> guard.requesterId().equals(query.actor().subject());
            case SUPPORT_PUBLIC_VIEW, SUPPORT_INTERNAL_VIEW -> query.allowedApplicationCodes().stream()
                .map(Enum::name)
                .anyMatch(code -> code.equals(guard.applicationCode()));
            case AUDITOR_POLICY_VIEW -> false;
        };
    }

    private TicketTimelineCursor decodeAndMatch(
        String token, String expectedTicketId, String expectedScopeFingerprint, String expectedViewType, String expectedPrincipalSubject, Instant now
    ) {
        try {
            TicketTimelineCursor cursor = cursorCodec.decode(token, now);
            cursorCodec.requireMatch(cursor, expectedTicketId, expectedScopeFingerprint, expectedViewType, expectedPrincipalSubject);
            return cursor;
        } catch (InvalidCursorException e) {
            telemetry.recordTimelineInvalidCursor();
            throw e;
        }
    }

    private void auditSensitiveRead(TicketTimelineQuery query, TicketTimelineViewType viewType, Instant now) {
        try {
            sensitiveReadAuditPort.recordSensitiveRead(new SensitiveReadAuditEntry(
                query.actor().actorType(),
                query.actor().subject(),
                query.actor().clientId(),
                query.ticketId().value().toString(),
                viewType.name(),
                FIELDS_POLICY_VERSION,
                currentTraceId(),
                "SUCCESS",
                now
            ));
        } catch (RuntimeException e) {
            telemetry.recordTimelineSensitiveReadAuditFailure();
            throw new SensitiveReadAuditFailureException(e);
        }
    }

    private static String scopeFingerprint(Set<ApplicationCode> allowedApplicationCodes) {
        String canonical = allowedApplicationCodes.stream().map(Enum::name).sorted().collect(Collectors.joining(","));
        return "sha256:" + sha256Hex(canonical);
    }

    private static String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    private static String currentTraceId() {
        String traceId = MDC.get("traceId");
        return traceId == null ? "" : traceId;
    }
}
