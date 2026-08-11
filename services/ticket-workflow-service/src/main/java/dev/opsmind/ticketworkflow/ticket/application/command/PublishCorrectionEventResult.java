package dev.opsmind.ticketworkflow.ticket.application.command;

import dev.opsmind.ticketworkflow.ticket.domain.value.ReconciliationDecision;

import java.util.UUID;

/** SPEC-TW-039 api-contract §"Response 200". */
public record PublishCorrectionEventResult(
    UUID recoveryId,
    ReconciliationDecision decision,
    String eventName,
    boolean replayed
) {
}
