package dev.opsmind.ticketworkflow.ticket.application.command;

import dev.opsmind.ticketworkflow.ticket.domain.value.SupportQueueId;

import java.util.Objects;

/**
 * SPEC-TW-033 internal policy endpoint input. {@code caller} is the trusted,
 * JWT-derived internal principal invoking the policy check (never
 * client-suppliable — resolved by the controller exactly like {@code
 * AutoCloseTicketController}'s scheduler principal); {@code targetActorId}/
 * {@code targetActorType}/{@code operation}/{@code supportQueueId} are the
 * subject and context the caller is asking the policy about, carried in the
 * request body per the API contract. {@code supportQueueId} is {@code null}
 * when the request omitted (or left blank) {@code context.supportQueueId}.
 */
public record EvaluateSupportQueueAuthorizationCommand(
    ActorContext caller,
    String ticketId,
    String targetActorId,
    String targetActorType,
    String operation,
    SupportQueueId supportQueueId,
    String correlationId,
    String traceId
) {

    public EvaluateSupportQueueAuthorizationCommand {
        Objects.requireNonNull(caller, "caller must not be null");
        Objects.requireNonNull(ticketId, "ticketId must not be null");
        Objects.requireNonNull(targetActorId, "targetActorId must not be null");
        Objects.requireNonNull(targetActorType, "targetActorType must not be null");
        Objects.requireNonNull(operation, "operation must not be null");
    }
}
