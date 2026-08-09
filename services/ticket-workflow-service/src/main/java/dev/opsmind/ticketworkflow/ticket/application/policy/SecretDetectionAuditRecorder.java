package dev.opsmind.ticketworkflow.ticket.application.policy;

import dev.opsmind.ticketworkflow.ticket.application.model.SecretDetectionDecisionEntry;
import dev.opsmind.ticketworkflow.ticket.application.observability.TicketTelemetry;
import dev.opsmind.ticketworkflow.ticket.application.port.out.ClockPort;
import dev.opsmind.ticketworkflow.ticket.application.port.out.SecretDetectionDecisionPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Single, shared entry point for recording SPEC-TW-035 Secret Detection
 * policy decisions (README §1: "Real business endpoints may call the same
 * application policy inline without exposing this internal endpoint").
 * {@link dev.opsmind.ticketworkflow.ticket.application.service.SecretDetectionPolicyApplicationService}
 * (the internal policy endpoint) and the hardened Ticket message / User
 * reply create paths all call this one component, so the decision ledger
 * and the low-cardinality metrics stay identical regardless of the call
 * path.
 */
@Component
public class SecretDetectionAuditRecorder {

    private static final Logger log = LoggerFactory.getLogger(SecretDetectionAuditRecorder.class);

    private final SecretDetectionDecisionPort decisionPort;
    private final ClockPort clock;
    private final TicketTelemetry telemetry;

    public SecretDetectionAuditRecorder(
        SecretDetectionDecisionPort decisionPort,
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
        persist(ticketId, actorId, actorType, operation, SecretDetectionDecisionCode.DECISION_ALLOW,
            SecretDetectionDecisionCode.ALLOWED, correlationId, traceId);
        telemetry.recordSecretDetectionPolicyDecision(SecretDetectionDecisionCode.ALLOWED);
    }

    /** Records a {@code DENY} decision. Also allowed to propagate: a rejected path is still required to be traceable. */
    public void recordDenied(String ticketId, String actorId, String actorType, String operation, String decisionCode, String correlationId, String traceId) {
        persist(ticketId, actorId, actorType, operation, SecretDetectionDecisionCode.DECISION_DENY,
            decisionCode, correlationId, traceId);
        telemetry.recordSecretDetectionPolicyDecision(decisionCode);
    }

    /**
     * Records a {@code FAIL_CLOSED} decision, best-effort only: a secondary
     * persistence failure here is logged and swallowed rather than raised —
     * it must never mask the caller's fail-closed response with a
     * different, unrelated exception.
     */
    public void recordFailClosed(String ticketId, String actorId, String actorType, String operation, String correlationId, String traceId) {
        try {
            persist(ticketId, actorId, actorType, operation, SecretDetectionDecisionCode.DECISION_FAIL_CLOSED,
                SecretDetectionDecisionCode.FAIL_CLOSED_DECISION_AUDIT, correlationId, traceId);
        } catch (RuntimeException e) {
            log.error("failed to persist a fail-closed Secret Detection decision", e);
        }
        telemetry.recordSecretDetectionPolicyFailClosed();
    }

    private void persist(String ticketId, String actorId, String actorType, String operation, String decision, String decisionCode, String correlationId, String traceId) {
        decisionPort.record(new SecretDetectionDecisionEntry(
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
