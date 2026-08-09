package dev.opsmind.ticketworkflow.ticket.application.service;

import dev.opsmind.ticketworkflow.ticket.application.command.EvaluateSupportQueueAuthorizationCommand;
import dev.opsmind.ticketworkflow.ticket.application.command.EvaluateSupportQueueAuthorizationResult;
import dev.opsmind.ticketworkflow.ticket.application.exception.SupportQueueAuthorizationConflictException;
import dev.opsmind.ticketworkflow.ticket.application.exception.SupportQueueAuthorizationDeniedException;
import dev.opsmind.ticketworkflow.ticket.application.exception.SupportQueueAuthorizationFailClosedException;
import dev.opsmind.ticketworkflow.ticket.application.exception.TicketAuthorizationException;
import dev.opsmind.ticketworkflow.ticket.application.observability.TicketTelemetry;
import dev.opsmind.ticketworkflow.ticket.application.policy.SupportQueueAuthorizationAuditRecorder;
import dev.opsmind.ticketworkflow.ticket.application.policy.SupportQueueAuthorizationDecisionCode;
import dev.opsmind.ticketworkflow.ticket.application.policy.SupportQueueAuthorizationPolicy;
import dev.opsmind.ticketworkflow.ticket.application.port.in.EvaluateSupportQueueAuthorizationUseCase;
import dev.opsmind.ticketworkflow.ticket.application.port.out.SupportQueueMembershipPort;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Service;

/**
 * SPEC-TW-033 Support Queue Authorization internal policy endpoint
 * (api-contract §"Internal Policy Endpoint"). Evaluation order mirrors
 * domain-rules exactly: caller admission first (this is a
 * trusted-service-to-service endpoint, not human-facing — mirrors {@code
 * AutoCloseTicketApplicationService}'s own {@code SERVICE}-actor-type
 * guard), then operation recognition, then target actor-type eligibility,
 * then context sufficiency, then Support Queue membership — each rejection
 * is recorded through the same {@link SupportQueueAuthorizationAuditRecorder}
 * that hardened Phase 01-08 endpoints call inline, and any unexpected
 * failure anywhere in the pipeline (including a failed audit write) fails
 * closed rather than defaulting to {@code ALLOW}.
 */
@Service
public class SupportQueueAuthorizationApplicationService implements EvaluateSupportQueueAuthorizationUseCase {

    static final String CALLER_REQUIRED_SCOPE = "internal:support-queue-authorization:evaluate";

    private final SupportQueueMembershipPort membershipPort;
    private final SupportQueueAuthorizationPolicy policy;
    private final SupportQueueAuthorizationAuditRecorder auditRecorder;
    private final TicketTelemetry telemetry;

    public SupportQueueAuthorizationApplicationService(
        SupportQueueMembershipPort membershipPort,
        SupportQueueAuthorizationPolicy policy,
        SupportQueueAuthorizationAuditRecorder auditRecorder,
        TicketTelemetry telemetry
    ) {
        this.membershipPort = membershipPort;
        this.policy = policy;
        this.auditRecorder = auditRecorder;
        this.telemetry = telemetry;
    }

    @Override
    public EvaluateSupportQueueAuthorizationResult evaluate(EvaluateSupportQueueAuthorizationCommand command) {
        Timer.Sample timer = telemetry.startSupportQueueAuthorizationTimer();
        try {
            if (!command.caller().hasScope(CALLER_REQUIRED_SCOPE)) {
                telemetry.recordSupportQueueAuthorizationCallerDenied();
                throw new TicketAuthorizationException(CALLER_REQUIRED_SCOPE);
            }
            try {
                return decide(command);
            } catch (SupportQueueAuthorizationDeniedException | SupportQueueAuthorizationConflictException expected) {
                throw expected;
            } catch (RuntimeException unexpected) {
                auditRecorder.recordFailClosed(
                    command.ticketId(), command.targetActorId(), command.targetActorType(), command.operation(),
                    SupportQueueAuthorizationDecisionCode.FAIL_CLOSED_UNEXPECTED_ERROR, command.correlationId(), command.traceId()
                );
                throw new SupportQueueAuthorizationFailClosedException(unexpected);
            }
        } finally {
            telemetry.stopSupportQueueAuthorizationTimer(timer);
        }
    }

    private EvaluateSupportQueueAuthorizationResult decide(EvaluateSupportQueueAuthorizationCommand command) {
        if (!policy.isRecognizedOperation(command.operation())) {
            deny(command, SupportQueueAuthorizationDecisionCode.OPERATION_NOT_SUPPORTED);
            throw new SupportQueueAuthorizationConflictException(SupportQueueAuthorizationDecisionCode.OPERATION_NOT_SUPPORTED);
        }
        if (!policy.isQueueScopedActorType(command.targetActorType())) {
            deny(command, SupportQueueAuthorizationDecisionCode.DENIED_ACTOR_TYPE);
            throw new SupportQueueAuthorizationDeniedException(SupportQueueAuthorizationDecisionCode.DENIED_ACTOR_TYPE);
        }
        if (command.supportQueueId() == null) {
            deny(command, SupportQueueAuthorizationDecisionCode.CONTEXT_REQUIRED);
            throw new SupportQueueAuthorizationConflictException(SupportQueueAuthorizationDecisionCode.CONTEXT_REQUIRED);
        }
        if (!membershipPort.isMember(command.targetActorId(), command.supportQueueId())) {
            deny(command, SupportQueueAuthorizationDecisionCode.DENIED_SCOPE);
            throw new SupportQueueAuthorizationDeniedException(SupportQueueAuthorizationDecisionCode.DENIED_SCOPE);
        }

        auditRecorder.recordAllowed(command.ticketId(), command.targetActorId(), command.targetActorType(), command.operation(), command.correlationId(), command.traceId());
        return new EvaluateSupportQueueAuthorizationResult(
            SupportQueueAuthorizationDecisionCode.DECISION_ALLOW, SupportQueueAuthorizationDecisionCode.ALLOWED, true
        );
    }

    private void deny(EvaluateSupportQueueAuthorizationCommand command, String decisionCode) {
        auditRecorder.recordDenied(
            command.ticketId(), command.targetActorId(), command.targetActorType(), command.operation(),
            decisionCode, command.correlationId(), command.traceId()
        );
    }
}
