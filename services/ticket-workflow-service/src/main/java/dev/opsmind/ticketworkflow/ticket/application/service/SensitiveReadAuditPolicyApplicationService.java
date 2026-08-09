package dev.opsmind.ticketworkflow.ticket.application.service;

import dev.opsmind.ticketworkflow.ticket.application.command.EvaluateSensitiveReadAuditCommand;
import dev.opsmind.ticketworkflow.ticket.application.command.EvaluateSensitiveReadAuditResult;
import dev.opsmind.ticketworkflow.ticket.application.exception.SensitiveReadAuditFailureException;
import dev.opsmind.ticketworkflow.ticket.application.exception.SensitiveReadAuditPolicyConflictException;
import dev.opsmind.ticketworkflow.ticket.application.exception.SensitiveReadAuditPolicyDeniedException;
import dev.opsmind.ticketworkflow.ticket.application.exception.TicketAuthorizationException;
import dev.opsmind.ticketworkflow.ticket.application.observability.TicketTelemetry;
import dev.opsmind.ticketworkflow.ticket.application.policy.SensitiveReadAuditDecisionCode;
import dev.opsmind.ticketworkflow.ticket.application.policy.SensitiveReadAuditDecisionRecorder;
import dev.opsmind.ticketworkflow.ticket.application.policy.SensitiveReadAuditPolicy;
import dev.opsmind.ticketworkflow.ticket.application.port.in.EvaluateSensitiveReadAuditUseCase;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Service;

/**
 * SPEC-TW-034 Sensitive Read Audit internal policy endpoint (api-contract
 * §"Internal Policy Endpoint"). Evaluation order mirrors domain-rules
 * exactly: caller admission first (this is a trusted-service-to-service
 * endpoint, not human-facing — mirrors {@code
 * SupportQueueAuthorizationApplicationService}'s own caller guard), then
 * operation recognition, then target actor-type eligibility, then the
 * required policy-decision audit write — each outcome is recorded through
 * {@link SensitiveReadAuditDecisionRecorder}, and a failed audit write
 * fails closed (reusing {@link SensitiveReadAuditFailureException}, the
 * same exception Get Ticket and Ticket Timeline already fail closed with)
 * rather than defaulting to {@code ALLOW}.
 */
@Service
public class SensitiveReadAuditPolicyApplicationService implements EvaluateSensitiveReadAuditUseCase {

    static final String CALLER_REQUIRED_SCOPE = "internal:sensitive-read-audit:evaluate";

    private final SensitiveReadAuditPolicy policy;
    private final SensitiveReadAuditDecisionRecorder auditRecorder;
    private final TicketTelemetry telemetry;

    public SensitiveReadAuditPolicyApplicationService(
        SensitiveReadAuditPolicy policy,
        SensitiveReadAuditDecisionRecorder auditRecorder,
        TicketTelemetry telemetry
    ) {
        this.policy = policy;
        this.auditRecorder = auditRecorder;
        this.telemetry = telemetry;
    }

    @Override
    public EvaluateSensitiveReadAuditResult evaluate(EvaluateSensitiveReadAuditCommand command) {
        Timer.Sample timer = telemetry.startSensitiveReadAuditPolicyTimer();
        try {
            if (!command.caller().hasScope(CALLER_REQUIRED_SCOPE)) {
                telemetry.recordSensitiveReadAuditPolicyCallerDenied();
                throw new TicketAuthorizationException(CALLER_REQUIRED_SCOPE);
            }
            try {
                return decide(command);
            } catch (SensitiveReadAuditPolicyDeniedException | SensitiveReadAuditPolicyConflictException expected) {
                throw expected;
            } catch (RuntimeException unexpected) {
                auditRecorder.recordFailClosed(
                    command.ticketId(), command.targetActorId(), command.targetActorType(), command.operation(),
                    command.correlationId(), command.traceId()
                );
                throw new SensitiveReadAuditFailureException(unexpected);
            }
        } finally {
            telemetry.stopSensitiveReadAuditPolicyTimer(timer);
        }
    }

    private EvaluateSensitiveReadAuditResult decide(EvaluateSensitiveReadAuditCommand command) {
        if (!policy.isRecognizedOperation(command.operation())) {
            auditRecorder.recordDenied(
                command.ticketId(), command.targetActorId(), command.targetActorType(), command.operation(),
                SensitiveReadAuditDecisionCode.OPERATION_NOT_SUPPORTED, command.correlationId(), command.traceId()
            );
            throw new SensitiveReadAuditPolicyConflictException(SensitiveReadAuditDecisionCode.OPERATION_NOT_SUPPORTED);
        }
        if (!policy.isReadEligibleActorType(command.targetActorType())) {
            auditRecorder.recordDenied(
                command.ticketId(), command.targetActorId(), command.targetActorType(), command.operation(),
                SensitiveReadAuditDecisionCode.DENIED_ACTOR_TYPE, command.correlationId(), command.traceId()
            );
            throw new SensitiveReadAuditPolicyDeniedException(SensitiveReadAuditDecisionCode.DENIED_ACTOR_TYPE);
        }

        // The required policy-decision audit write IS this policy's enforcement point (domain-rules:
        // "Sensitive details must not be returned when required audit persistence fails"): a failure here
        // propagates to the outer catch above and fails the whole evaluation closed, never returning ALLOW.
        auditRecorder.recordAllowed(command.ticketId(), command.targetActorId(), command.targetActorType(), command.operation(), command.correlationId(), command.traceId());
        return new EvaluateSensitiveReadAuditResult(
            SensitiveReadAuditDecisionCode.DECISION_ALLOW, SensitiveReadAuditDecisionCode.ALLOWED, true
        );
    }
}
