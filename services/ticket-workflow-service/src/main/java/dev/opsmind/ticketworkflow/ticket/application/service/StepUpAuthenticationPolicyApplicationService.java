package dev.opsmind.ticketworkflow.ticket.application.service;

import dev.opsmind.ticketworkflow.ticket.application.command.EvaluateStepUpAuthenticationCommand;
import dev.opsmind.ticketworkflow.ticket.application.command.EvaluateStepUpAuthenticationResult;
import dev.opsmind.ticketworkflow.ticket.application.exception.StepUpAuthenticationFailClosedException;
import dev.opsmind.ticketworkflow.ticket.application.exception.StepUpAuthenticationPolicyConflictException;
import dev.opsmind.ticketworkflow.ticket.application.exception.StepUpAuthenticationRequiredException;
import dev.opsmind.ticketworkflow.ticket.application.exception.TicketAuthorizationException;
import dev.opsmind.ticketworkflow.ticket.application.observability.TicketTelemetry;
import dev.opsmind.ticketworkflow.ticket.application.policy.StepUpAuthenticationAuditRecorder;
import dev.opsmind.ticketworkflow.ticket.application.policy.StepUpAuthenticationDecisionCode;
import dev.opsmind.ticketworkflow.ticket.application.policy.StepUpAuthenticationPolicy;
import dev.opsmind.ticketworkflow.ticket.application.port.in.EvaluateStepUpAuthenticationUseCase;
import dev.opsmind.ticketworkflow.ticket.application.port.out.ClockPort;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Service;

/**
 * SPEC-TW-036 Step-up Authentication internal policy endpoint (api-contract
 * §"Internal Policy Endpoint"). Evaluation order mirrors domain-rules
 * exactly: caller admission first (this is a trusted-service-to-service
 * endpoint, not human-facing — mirrors {@code
 * SupportQueueAuthorizationApplicationService}/{@code
 * SensitiveReadAuditPolicyApplicationService}/{@code
 * SecretDetectionPolicyApplicationService}'s own caller guard), then
 * operation recognition, then step-up proof validity — each outcome is
 * recorded through {@link StepUpAuthenticationAuditRecorder}, and any
 * unexpected failure (including a failed decision-audit write) fails
 * closed rather than defaulting to {@code ALLOW}.
 */
@Service
public class StepUpAuthenticationPolicyApplicationService implements EvaluateStepUpAuthenticationUseCase {

    static final String CALLER_REQUIRED_SCOPE = "internal:step-up:evaluate";

    private final StepUpAuthenticationPolicy policy;
    private final StepUpAuthenticationAuditRecorder auditRecorder;
    private final ClockPort clock;
    private final TicketTelemetry telemetry;

    public StepUpAuthenticationPolicyApplicationService(
        StepUpAuthenticationPolicy policy,
        StepUpAuthenticationAuditRecorder auditRecorder,
        ClockPort clock,
        TicketTelemetry telemetry
    ) {
        this.policy = policy;
        this.auditRecorder = auditRecorder;
        this.clock = clock;
        this.telemetry = telemetry;
    }

    @Override
    public EvaluateStepUpAuthenticationResult evaluate(EvaluateStepUpAuthenticationCommand command) {
        Timer.Sample timer = telemetry.startStepUpAuthenticationPolicyTimer();
        try {
            if (!command.caller().hasScope(CALLER_REQUIRED_SCOPE)) {
                telemetry.recordStepUpAuthenticationPolicyCallerDenied();
                throw new TicketAuthorizationException(CALLER_REQUIRED_SCOPE);
            }
            try {
                return decide(command);
            } catch (StepUpAuthenticationRequiredException | StepUpAuthenticationPolicyConflictException expected) {
                throw expected;
            } catch (RuntimeException unexpected) {
                auditRecorder.recordFailClosed(
                    command.ticketId(), command.targetActorId(), command.targetActorType(), command.operation(),
                    command.correlationId(), command.traceId()
                );
                throw new StepUpAuthenticationFailClosedException(unexpected);
            }
        } finally {
            telemetry.stopStepUpAuthenticationPolicyTimer(timer);
        }
    }

    private EvaluateStepUpAuthenticationResult decide(EvaluateStepUpAuthenticationCommand command) {
        if (!policy.isRecognizedOperation(command.operation())) {
            auditRecorder.recordDenied(
                command.ticketId(), command.targetActorId(), command.targetActorType(), command.operation(),
                StepUpAuthenticationDecisionCode.OPERATION_NOT_SUPPORTED, command.correlationId(), command.traceId()
            );
            throw new StepUpAuthenticationPolicyConflictException(StepUpAuthenticationDecisionCode.OPERATION_NOT_SUPPORTED);
        }

        String decisionCode = policy.classify(command.proof(), clock.now());
        if (!StepUpAuthenticationDecisionCode.ALLOWED.equals(decisionCode)) {
            auditRecorder.recordDenied(
                command.ticketId(), command.targetActorId(), command.targetActorType(), command.operation(),
                decisionCode, command.correlationId(), command.traceId()
            );
            throw new StepUpAuthenticationRequiredException(decisionCode);
        }

        auditRecorder.recordAllowed(command.ticketId(), command.targetActorId(), command.targetActorType(), command.operation(), command.correlationId(), command.traceId());
        return new EvaluateStepUpAuthenticationResult(
            StepUpAuthenticationDecisionCode.DECISION_ALLOW, StepUpAuthenticationDecisionCode.ALLOWED, true
        );
    }
}
