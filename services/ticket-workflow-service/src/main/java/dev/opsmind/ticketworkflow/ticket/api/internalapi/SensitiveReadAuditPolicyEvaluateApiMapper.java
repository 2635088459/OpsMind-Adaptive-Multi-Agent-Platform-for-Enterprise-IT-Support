package dev.opsmind.ticketworkflow.ticket.api.internalapi;

import dev.opsmind.ticketworkflow.ticket.application.command.ActorContext;
import dev.opsmind.ticketworkflow.ticket.application.command.EvaluateSensitiveReadAuditCommand;
import dev.opsmind.ticketworkflow.ticket.application.command.EvaluateSensitiveReadAuditResult;
import org.springframework.stereotype.Component;

@Component
public class SensitiveReadAuditPolicyEvaluateApiMapper {

    public EvaluateSensitiveReadAuditCommand toCommand(
        SensitiveReadAuditPolicyEvaluateRequest request,
        ActorContext caller,
        String correlationId,
        String traceId
    ) {
        return new EvaluateSensitiveReadAuditCommand(
            caller,
            request.ticketId(),
            request.actorId(),
            request.actorType(),
            request.operation(),
            correlationId,
            traceId
        );
    }

    public SensitiveReadAuditPolicyEvaluateResponse toResponse(EvaluateSensitiveReadAuditResult result) {
        return new SensitiveReadAuditPolicyEvaluateResponse(result.decision(), result.decisionCode(), result.auditRequired());
    }
}
