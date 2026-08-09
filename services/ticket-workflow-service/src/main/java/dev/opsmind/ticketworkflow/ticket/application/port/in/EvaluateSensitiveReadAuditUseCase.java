package dev.opsmind.ticketworkflow.ticket.application.port.in;

import dev.opsmind.ticketworkflow.ticket.application.command.EvaluateSensitiveReadAuditCommand;
import dev.opsmind.ticketworkflow.ticket.application.command.EvaluateSensitiveReadAuditResult;

public interface EvaluateSensitiveReadAuditUseCase {

    EvaluateSensitiveReadAuditResult evaluate(EvaluateSensitiveReadAuditCommand command);
}
