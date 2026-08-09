package dev.opsmind.ticketworkflow.ticket.application.command;

import java.util.Objects;

/**
 * SPEC-TW-034 internal policy endpoint input. {@code caller} is the trusted,
 * JWT-derived internal principal invoking the policy check (never
 * client-suppliable — resolved by the controller exactly like {@code
 * AutoCloseTicketController}'s scheduler principal, and {@code
 * SupportQueueAuthorizationController}'s caller); {@code targetActorId}/
 * {@code targetActorType}/{@code operation} are the subject and read
 * operation the caller is asking the policy about, carried in the request
 * body per the API contract. The request body's {@code context}, present
 * only for shape parity with SPEC-TW-033's shared contract template, is not
 * part of this policy's decision (a sensitive read is not Support-Queue
 * scoped) and is therefore not represented here.
 */
public record EvaluateSensitiveReadAuditCommand(
    ActorContext caller,
    String ticketId,
    String targetActorId,
    String targetActorType,
    String operation,
    String correlationId,
    String traceId
) {

    public EvaluateSensitiveReadAuditCommand {
        Objects.requireNonNull(caller, "caller must not be null");
        Objects.requireNonNull(ticketId, "ticketId must not be null");
        Objects.requireNonNull(targetActorId, "targetActorId must not be null");
        Objects.requireNonNull(targetActorType, "targetActorType must not be null");
        Objects.requireNonNull(operation, "operation must not be null");
    }
}
