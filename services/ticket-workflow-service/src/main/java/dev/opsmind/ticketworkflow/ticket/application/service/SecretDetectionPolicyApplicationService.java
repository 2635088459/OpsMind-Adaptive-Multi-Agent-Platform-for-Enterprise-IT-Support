package dev.opsmind.ticketworkflow.ticket.application.service;

import dev.opsmind.ticketworkflow.ticket.application.command.EvaluateSecretDetectionCommand;
import dev.opsmind.ticketworkflow.ticket.application.command.EvaluateSecretDetectionResult;
import dev.opsmind.ticketworkflow.ticket.application.exception.SecretDetectionFailClosedException;
import dev.opsmind.ticketworkflow.ticket.application.exception.SecretDetectionPolicyConflictException;
import dev.opsmind.ticketworkflow.ticket.application.exception.SecretDetectionPolicyDeniedException;
import dev.opsmind.ticketworkflow.ticket.application.exception.TicketAuthorizationException;
import dev.opsmind.ticketworkflow.ticket.application.observability.TicketTelemetry;
import dev.opsmind.ticketworkflow.ticket.application.policy.SecretDetectionAuditRecorder;
import dev.opsmind.ticketworkflow.ticket.application.policy.SecretDetectionDecisionCode;
import dev.opsmind.ticketworkflow.ticket.application.policy.SecretDetectionPolicy;
import dev.opsmind.ticketworkflow.ticket.application.port.in.EvaluateSecretDetectionUseCase;
import dev.opsmind.ticketworkflow.ticket.domain.value.SecretPatternCategory;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Service;

/**
 * SPEC-TW-035 Secret Detection internal policy endpoint (api-contract
 * §"Internal Policy Endpoint"). Evaluation order mirrors domain-rules
 * exactly: caller admission first (this is a trusted-service-to-service
 * endpoint, not human-facing — mirrors {@code
 * SupportQueueAuthorizationApplicationService}/{@code
 * SensitiveReadAuditPolicyApplicationService}'s own caller guard), then
 * operation recognition, then free-text classification — each outcome is
 * recorded through {@link SecretDetectionAuditRecorder}, and any unexpected
 * failure (including a failed decision-audit write) fails closed rather
 * than defaulting to {@code ALLOW}.
 */
@Service
public class SecretDetectionPolicyApplicationService implements EvaluateSecretDetectionUseCase {

    static final String CALLER_REQUIRED_SCOPE = "internal:secret-detection:evaluate";

    private final SecretDetectionPolicy policy;
    private final SecretDetectionAuditRecorder auditRecorder;
    private final TicketTelemetry telemetry;

    public SecretDetectionPolicyApplicationService(
        SecretDetectionPolicy policy,
        SecretDetectionAuditRecorder auditRecorder,
        TicketTelemetry telemetry
    ) {
        this.policy = policy;
        this.auditRecorder = auditRecorder;
        this.telemetry = telemetry;
    }

    @Override
    public EvaluateSecretDetectionResult evaluate(EvaluateSecretDetectionCommand command) {
        Timer.Sample timer = telemetry.startSecretDetectionPolicyTimer();
        try {
            if (!command.caller().hasScope(CALLER_REQUIRED_SCOPE)) {
                telemetry.recordSecretDetectionPolicyCallerDenied();
                throw new TicketAuthorizationException(CALLER_REQUIRED_SCOPE);
            }
            try {
                return decide(command);
            } catch (SecretDetectionPolicyDeniedException | SecretDetectionPolicyConflictException expected) {
                throw expected;
            } catch (RuntimeException unexpected) {
                auditRecorder.recordFailClosed(
                    command.ticketId(), command.targetActorId(), command.targetActorType(), command.operation(),
                    command.correlationId(), command.traceId()
                );
                throw new SecretDetectionFailClosedException(unexpected);
            }
        } finally {
            telemetry.stopSecretDetectionPolicyTimer(timer);
        }
    }

    private EvaluateSecretDetectionResult decide(EvaluateSecretDetectionCommand command) {
        if (!policy.isRecognizedOperation(command.operation())) {
            auditRecorder.recordDenied(
                command.ticketId(), command.targetActorId(), command.targetActorType(), command.operation(),
                SecretDetectionDecisionCode.OPERATION_NOT_SUPPORTED, command.correlationId(), command.traceId()
            );
            throw new SecretDetectionPolicyConflictException(SecretDetectionDecisionCode.OPERATION_NOT_SUPPORTED);
        }

        SecretPatternCategory category = policy.classify(command.content());
        if (category != SecretPatternCategory.NONE) {
            String decisionCode = policy.decisionCodeFor(category);
            auditRecorder.recordDenied(
                command.ticketId(), command.targetActorId(), command.targetActorType(), command.operation(),
                decisionCode, command.correlationId(), command.traceId()
            );
            throw new SecretDetectionPolicyDeniedException(decisionCode);
        }

        auditRecorder.recordAllowed(command.ticketId(), command.targetActorId(), command.targetActorType(), command.operation(), command.correlationId(), command.traceId());
        return new EvaluateSecretDetectionResult(
            SecretDetectionDecisionCode.DECISION_ALLOW, SecretDetectionDecisionCode.ALLOWED, true
        );
    }
}
