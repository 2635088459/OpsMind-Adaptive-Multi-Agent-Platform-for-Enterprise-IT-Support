package dev.opsmind.ticketworkflow.ticket.api.internalapi;

import dev.opsmind.ticketworkflow.ticket.api.exception.RequestValidationException;
import dev.opsmind.ticketworkflow.ticket.application.command.ActorContext;
import dev.opsmind.ticketworkflow.ticket.application.command.EvaluateSupportQueueAuthorizationCommand;
import dev.opsmind.ticketworkflow.ticket.application.command.EvaluateSupportQueueAuthorizationResult;
import dev.opsmind.ticketworkflow.ticket.domain.value.SupportQueueId;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
public class SupportQueueAuthorizationEvaluateApiMapper {

    public EvaluateSupportQueueAuthorizationCommand toCommand(
        SupportQueueAuthorizationEvaluateRequest request,
        ActorContext caller,
        String correlationId,
        String traceId
    ) {
        String rawSupportQueueId = request.context() == null ? null : request.context().supportQueueId();
        return new EvaluateSupportQueueAuthorizationCommand(
            caller,
            request.ticketId(),
            request.actorId(),
            request.actorType(),
            request.operation(),
            parseSupportQueueId(rawSupportQueueId),
            correlationId,
            traceId
        );
    }

    public SupportQueueAuthorizationEvaluateResponse toResponse(EvaluateSupportQueueAuthorizationResult result) {
        return new SupportQueueAuthorizationEvaluateResponse(result.decision(), result.decisionCode(), result.auditRequired());
    }

    /** A blank {@code supportQueueId} is "no context supplied" (policy conflict, not a shape error); a non-blank but malformed one is a {@code 400}. */
    private SupportQueueId parseSupportQueueId(String rawSupportQueueId) {
        if (rawSupportQueueId == null || rawSupportQueueId.isBlank()) {
            return null;
        }
        try {
            return SupportQueueId.of(UUID.fromString(rawSupportQueueId.trim()));
        } catch (IllegalArgumentException e) {
            throw new RequestValidationException("context.supportQueueId must be a valid UUID");
        }
    }
}
