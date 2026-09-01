package dev.opsmind.ticketworkflow.ticket.api.internalapi;

import dev.opsmind.ticketworkflow.ticket.application.service.OutboxDispatchApplicationService;

public record OutboxDispatchResponse(int claimed, int published, int retried, int deadLettered) {

    public static OutboxDispatchResponse from(OutboxDispatchApplicationService.DrainResult result) {
        return new OutboxDispatchResponse(result.claimed(), result.published(), result.retried(), result.deadLettered());
    }
}
