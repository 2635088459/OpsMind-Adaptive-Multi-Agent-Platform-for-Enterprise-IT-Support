package dev.opsmind.ticketworkflow.ticket.api.internalapi;

import dev.opsmind.ticketworkflow.ticket.application.command.ActorContext;
import dev.opsmind.ticketworkflow.ticket.application.command.EvaluateSecretDetectionCommand;
import dev.opsmind.ticketworkflow.ticket.application.command.EvaluateSecretDetectionResult;
import org.springframework.stereotype.Component;

@Component
public class SecretDetectionEvaluateApiMapper {

    public EvaluateSecretDetectionCommand toCommand(
        SecretDetectionEvaluateRequest request,
        ActorContext caller,
        String correlationId,
        String traceId
    ) {
        return new EvaluateSecretDetectionCommand(
            caller,
            request.ticketId(),
            request.actorId(),
            request.actorType(),
            request.operation(),
            request.content(),
            correlationId,
            traceId
        );
    }

    public SecretDetectionEvaluateResponse toResponse(EvaluateSecretDetectionResult result) {
        return new SecretDetectionEvaluateResponse(result.decision(), result.decisionCode(), result.auditRequired());
    }
}
