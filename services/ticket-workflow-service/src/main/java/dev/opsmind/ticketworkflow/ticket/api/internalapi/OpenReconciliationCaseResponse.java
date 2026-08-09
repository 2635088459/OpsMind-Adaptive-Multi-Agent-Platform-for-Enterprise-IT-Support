package dev.opsmind.ticketworkflow.ticket.api.internalapi;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.UUID;

/** SPEC-TW-037 api-contract §"Response 200". */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record OpenReconciliationCaseResponse(
    String decision,
    UUID recoveryId,
    String eventName
) {
}
