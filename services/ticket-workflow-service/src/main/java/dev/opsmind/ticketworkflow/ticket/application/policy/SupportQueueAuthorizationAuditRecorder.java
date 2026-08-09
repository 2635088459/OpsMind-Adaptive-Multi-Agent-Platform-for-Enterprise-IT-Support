package dev.opsmind.ticketworkflow.ticket.application.policy;

import dev.opsmind.ticketworkflow.ticket.application.model.SupportQueueAuthorizationDecisionEntry;
import dev.opsmind.ticketworkflow.ticket.application.observability.TicketTelemetry;
import dev.opsmind.ticketworkflow.ticket.application.port.out.ClockPort;
import dev.opsmind.ticketworkflow.ticket.application.port.out.SupportQueueAuthorizationDecisionPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Single, shared entry point for recording SPEC-TW-033 Support Queue
 * authorization decisions (README §1: "Real business endpoints may call the
 * same application policy inline without exposing this internal endpoint").
 * {@link dev.opsmind.ticketworkflow.ticket.application.service.SupportQueueAuthorizationApplicationService}
 * (the internal policy endpoint) and every hardened Phase 01-08 business
 * endpoint call this one component, so the decision ledger and the
 * low-cardinality metrics stay identical regardless of the call path.
 */
@Component
public class SupportQueueAuthorizationAuditRecorder {

    private static final Logger log = LoggerFactory.getLogger(SupportQueueAuthorizationAuditRecorder.class);

    private final SupportQueueAuthorizationDecisionPort decisionPort;
    private final ClockPort clock;
    private final TicketTelemetry telemetry;

    public SupportQueueAuthorizationAuditRecorder(
        SupportQueueAuthorizationDecisionPort decisionPort,
        ClockPort clock,
        TicketTelemetry telemetry
    ) {
        this.decisionPort = decisionPort;
        this.clock = clock;
        this.telemetry = telemetry;
    }

    /**
     * Records an {@code ALLOW} decision. Deliberately allowed to propagate a
     * persistence failure: an allow that cannot be durably recorded must
     * fail closed rather than being silently granted.
     */
    public void recordAllowed(String ticketId, String actorId, String actorType, String operation, String correlationId, String traceId) {
        persist(ticketId, actorId, actorType, operation, SupportQueueAuthorizationDecisionCode.DECISION_ALLOW,
            SupportQueueAuthorizationDecisionCode.ALLOWED, correlationId, traceId);
        telemetry.recordSupportQueueAuthorizationDecision(SupportQueueAuthorizationDecisionCode.ALLOWED);
    }

    /** Records a {@code DENY} decision. Also allowed to propagate: a rejected path is still required to be traceable. */
    public void recordDenied(String ticketId, String actorId, String actorType, String operation, String decisionCode, String correlationId, String traceId) {
        persist(ticketId, actorId, actorType, operation, SupportQueueAuthorizationDecisionCode.DECISION_DENY,
            decisionCode, correlationId, traceId);
        telemetry.recordSupportQueueAuthorizationDecision(decisionCode);
    }

    /**
     * Records a {@code FAIL_CLOSED} decision, best-effort only: this is
     * itself already the last resort after something unexpected went wrong,
     * so a secondary persistence failure here is logged and swallowed
     * rather than raised — it must never mask the caller's fail-closed
     * response with a different, unrelated exception.
     */
    public void recordFailClosed(String ticketId, String actorId, String actorType, String operation, String decisionCode, String correlationId, String traceId) {
        try {
            persist(ticketId, actorId, actorType, operation, SupportQueueAuthorizationDecisionCode.DECISION_FAIL_CLOSED,
                decisionCode, correlationId, traceId);
        } catch (RuntimeException e) {
            log.error("failed to persist a fail-closed Support Queue authorization decision", e);
        }
        telemetry.recordSupportQueueAuthorizationFailClosed();
    }

    private void persist(String ticketId, String actorId, String actorType, String operation, String decision, String decisionCode, String correlationId, String traceId) {
        decisionPort.record(new SupportQueueAuthorizationDecisionEntry(
            UUID.randomUUID(),
            blankToNull(ticketId),
            actorId,
            actorType,
            operation,
            decision,
            decisionCode,
            blankToNull(correlationId),
            blankToNull(traceId),
            clock.now()
        ));
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
