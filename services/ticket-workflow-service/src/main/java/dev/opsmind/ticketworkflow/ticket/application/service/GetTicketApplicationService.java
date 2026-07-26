package dev.opsmind.ticketworkflow.ticket.application.service;

import dev.opsmind.ticketworkflow.ticket.application.exception.SensitiveReadAuditFailureException;
import dev.opsmind.ticketworkflow.ticket.application.exception.TicketAuthorizationException;
import dev.opsmind.ticketworkflow.ticket.application.exception.TicketNotFoundException;
import dev.opsmind.ticketworkflow.ticket.application.model.SensitiveReadAuditEntry;
import dev.opsmind.ticketworkflow.ticket.application.observability.TicketTelemetry;
import dev.opsmind.ticketworkflow.ticket.application.policy.TicketResourceAccessPolicy;
import dev.opsmind.ticketworkflow.ticket.application.policy.TicketViewPolicy;
import dev.opsmind.ticketworkflow.ticket.application.port.in.GetTicketUseCase;
import dev.opsmind.ticketworkflow.ticket.application.port.out.ClockPort;
import dev.opsmind.ticketworkflow.ticket.application.port.out.SensitiveReadAuditPort;
import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketQueryPort;
import dev.opsmind.ticketworkflow.ticket.application.query.ConditionalGetResult;
import dev.opsmind.ticketworkflow.ticket.application.query.GetTicketQuery;
import dev.opsmind.ticketworkflow.ticket.application.query.GetTicketResult;
import dev.opsmind.ticketworkflow.ticket.application.query.TicketViewType;
import io.micrometer.core.instrument.Timer;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * Query-side application service for Get Ticket (SPEC-TW-002). Runs
 * authentication (upstream, via Spring Security), coarse-grained scope
 * authorization, resource-level authorization (pushed into SQL via {@link
 * TicketResourceAccessPolicy}), sensitive-read audit, and conditional-GET
 * evaluation, strictly in that order — a {@code 304} is never returned
 * before authorization has run (SPEC-TW-002 §15).
 */
@Service
public class GetTicketApplicationService implements GetTicketUseCase {

    private static final String OPERATION_ID = "getTicket";
    private static final String FIELDS_POLICY_VERSION = "1";

    private final TicketQueryPort ticketQueryPort;
    private final SensitiveReadAuditPort sensitiveReadAuditPort;
    private final TicketViewPolicy viewPolicy;
    private final TicketResourceAccessPolicy accessPolicy;
    private final TicketTelemetry telemetry;
    private final ClockPort clock;

    public GetTicketApplicationService(
        TicketQueryPort ticketQueryPort,
        SensitiveReadAuditPort sensitiveReadAuditPort,
        TicketViewPolicy viewPolicy,
        TicketResourceAccessPolicy accessPolicy,
        TicketTelemetry telemetry,
        ClockPort clock
    ) {
        this.ticketQueryPort = ticketQueryPort;
        this.sensitiveReadAuditPort = sensitiveReadAuditPort;
        this.viewPolicy = viewPolicy;
        this.accessPolicy = accessPolicy;
        this.telemetry = telemetry;
        this.clock = clock;
    }

    @Override
    public ConditionalGetResult get(GetTicketQuery query) {
        Timer.Sample timerSample = telemetry.startGetTimer();
        try {
            TicketViewType viewType = viewPolicy.resolve(query.actor());
            String requiredScope = viewPolicy.requiredScope(viewType);

            if (viewType == TicketViewType.AUDITOR_VIEW || !query.actor().hasScope(requiredScope)) {
                telemetry.recordAuthorizationDenied(OPERATION_ID);
                telemetry.recordGetAuthorizationDenied();
                throw new TicketAuthorizationException(requiredScope);
            }

            Optional<GetTicketResult> found = accessPolicy.loadAuthorizedView(ticketQueryPort, viewType, query);
            if (found.isEmpty()) {
                telemetry.recordGetNotFound();
                throw new TicketNotFoundException();
            }
            GetTicketResult result = found.get();

            if (viewType == TicketViewType.SUPPORT_VIEW) {
                auditSensitiveRead(query, viewType);
            }

            telemetry.recordGet(viewType);

            if (isNotModified(query.ifNoneMatch(), result.version())) {
                telemetry.recordGetNotModified();
                return new ConditionalGetResult.NotModified(result.version());
            }

            return new ConditionalGetResult.Found(result);
        } finally {
            telemetry.stopGetTimer(timerSample);
        }
    }

    private void auditSensitiveRead(GetTicketQuery query, TicketViewType viewType) {
        try {
            sensitiveReadAuditPort.recordSensitiveRead(new SensitiveReadAuditEntry(
                query.actor().actorType(),
                query.actor().subject(),
                query.actor().clientId(),
                query.ticketId().toString(),
                viewType,
                FIELDS_POLICY_VERSION,
                currentTraceId(),
                "SUCCESS",
                clock.now()
            ));
        } catch (RuntimeException e) {
            telemetry.recordSensitiveReadAuditFailure();
            throw new SensitiveReadAuditFailureException(e);
        }
    }

    private boolean isNotModified(String ifNoneMatch, long version) {
        if (ifNoneMatch == null || ifNoneMatch.isBlank()) {
            return false;
        }
        return ifNoneMatch.replace("\"", "").trim().equals(String.valueOf(version));
    }

    private String currentTraceId() {
        String traceId = MDC.get("traceId");
        return traceId == null ? "" : traceId;
    }
}
