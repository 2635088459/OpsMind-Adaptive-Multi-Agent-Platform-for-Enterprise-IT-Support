package dev.opsmind.ticketworkflow.ticket.api.internalapi;

import dev.opsmind.ticketworkflow.ticket.api.exception.RequestValidationException;
import dev.opsmind.ticketworkflow.ticket.application.command.ActorContext;
import dev.opsmind.ticketworkflow.ticket.application.command.EvaluateStepUpAuthenticationCommand;
import dev.opsmind.ticketworkflow.ticket.application.command.EvaluateStepUpAuthenticationResult;
import dev.opsmind.ticketworkflow.ticket.application.command.StepUpProof;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.format.DateTimeParseException;

@Component
public class StepUpAuthenticationEvaluateApiMapper {

    public EvaluateStepUpAuthenticationCommand toCommand(
        StepUpAuthenticationEvaluateRequest request,
        ActorContext caller,
        String correlationId,
        String traceId
    ) {
        return new EvaluateStepUpAuthenticationCommand(
            caller,
            request.ticketId(),
            request.actorId(),
            request.actorType(),
            request.operation(),
            toStepUpProof(request.stepUpProof()),
            correlationId,
            traceId
        );
    }

    public StepUpAuthenticationEvaluateResponse toResponse(EvaluateStepUpAuthenticationResult result) {
        return new StepUpAuthenticationEvaluateResponse(result.decision(), result.decisionCode(), result.auditRequired());
    }

    private StepUpProof toStepUpProof(StepUpAuthenticationEvaluateRequest.StepUpProofPayload payload) {
        if (payload == null) {
            return null;
        }
        return new StepUpProof(
            payload.proofId(),
            payload.method(),
            parseInstant(payload.verifiedAt(), "stepUpProof.verifiedAt"),
            parseInstant(payload.expiresAt(), "stepUpProof.expiresAt")
        );
    }

    private Instant parseInstant(String rawValue, String fieldName) {
        if (rawValue == null || rawValue.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(rawValue.trim());
        } catch (DateTimeParseException e) {
            throw new RequestValidationException(fieldName + " must be a valid ISO-8601 instant");
        }
    }
}
