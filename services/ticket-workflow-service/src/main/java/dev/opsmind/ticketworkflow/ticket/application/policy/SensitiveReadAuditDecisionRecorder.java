package dev.opsmind.ticketworkflow.ticket.application.policy;

import dev.opsmind.ticketworkflow.ticket.application.model.SensitiveReadAuditDecisionEntry;
import dev.opsmind.ticketworkflow.ticket.application.observability.TicketTelemetry;
import dev.opsmind.ticketworkflow.ticket.application.port.out.ClockPort;
import dev.opsmind.ticketworkflow.ticket.application.port.out.SensitiveReadAuditDecisionPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Single, shared entry point for recording SPEC-TW-034 Sensitive Read Audit
 * policy decisions (README §1: "Real business endpoints may call the same
 * application policy inline without exposing this internal endpoint").
 * {@link dev.opsmind.ticketworkflow.ticket.application.service.SensitiveReadAuditPolicyApplicationService}
 * (the internal policy endpoint) and the hardened Get Ticket / Ticket
 * Timeline read paths all call this one component, so the decision ledger
 * and the low-cardinality metrics stay identical regardless of the call
 * path. Distinct from {@code SensitiveReadAuditPort}: that port writes the
 * required {@code ticket.audit_records} business trail a sensitive read
 * cannot proceed without (SPEC-TW-002 §16, SPEC-TW-006 §23, unchanged by
 * this SPEC); this component writes the SPEC-TW-034 policy-decision trail
 * alongside it.
 */
@Component
public class SensitiveReadAuditDecisionRecorder {

    private static final Logger log = LoggerFactory.getLogger(SensitiveReadAuditDecisionRecorder.class);

    private final SensitiveReadAuditDecisionPort decisionPort;
    private final ClockPort clock;
    private final TicketTelemetry telemetry;

    public SensitiveReadAuditDecisionRecorder(
        SensitiveReadAuditDecisionPort decisionPort,
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
        persist(ticketId, actorId, actorType, operation, SensitiveReadAuditDecisionCode.DECISION_ALLOW,
            SensitiveReadAuditDecisionCode.ALLOWED, correlationId, traceId);
        telemetry.recordSensitiveReadAuditPolicyDecision(SensitiveReadAuditDecisionCode.ALLOWED);
    }

    /** Records a {@code DENY} decision. Also allowed to propagate: a rejected path is still required to be traceable. */
    public void recordDenied(String ticketId, String actorId, String actorType, String operation, String decisionCode, String correlationId, String traceId) {
        persist(ticketId, actorId, actorType, operation, SensitiveReadAuditDecisionCode.DECISION_DENY,
            decisionCode, correlationId, traceId);
        telemetry.recordSensitiveReadAuditPolicyDecision(decisionCode);
    }

    /**
     * Records a {@code FAIL_CLOSED} decision, best-effort only: this is
     * itself already the last resort after the required audit write failed,
     * so a secondary persistence failure here is logged and swallowed
     * rather than raised — it must never mask the caller's fail-closed
     * response with a different, unrelated exception.
     */
    public void recordFailClosed(String ticketId, String actorId, String actorType, String operation, String correlationId, String traceId) {
        try {
            persist(ticketId, actorId, actorType, operation, SensitiveReadAuditDecisionCode.DECISION_FAIL_CLOSED,
                SensitiveReadAuditDecisionCode.FAIL_CLOSED_AUDIT_PERSISTENCE, correlationId, traceId);
        } catch (RuntimeException e) {
            log.error("failed to persist a fail-closed Sensitive Read Audit decision", e);
        }
        telemetry.recordSensitiveReadAuditPolicyFailClosed();
    }

    private void persist(String ticketId, String actorId, String actorType, String operation, String decision, String decisionCode, String correlationId, String traceId) {
        decisionPort.record(new SensitiveReadAuditDecisionEntry(
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
