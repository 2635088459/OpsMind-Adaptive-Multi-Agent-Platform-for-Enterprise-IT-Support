package dev.opsmind.ticketworkflow.ticket.api.internalapi;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.UUID;

/** SPEC-TW-040 api-contract §"Response 200". */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record ExecuteCompensationResponse(
    String decision,
    UUID recoveryId,
    String eventName
) {
}
