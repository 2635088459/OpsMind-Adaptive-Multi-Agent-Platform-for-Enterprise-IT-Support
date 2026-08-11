package dev.opsmind.ticketworkflow.ticket.api.internalapi;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.UUID;

/** SPEC-TW-038 api-contract §"Response 200". */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record ReplayEventResponse(
    String decision,
    UUID recoveryId,
    String eventName
) {
}
