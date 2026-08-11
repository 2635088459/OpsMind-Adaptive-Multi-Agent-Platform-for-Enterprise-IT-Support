package dev.opsmind.ticketworkflow.ticket.application.command;

import dev.opsmind.ticketworkflow.ticket.domain.value.ReconciliationDecision;

import java.util.UUID;

/** SPEC-TW-040 api-contract §"Response 200". */
public record ExecuteCompensationResult(
    UUID recoveryId,
    ReconciliationDecision decision,
    String eventName,
    boolean replayed
) {
}
