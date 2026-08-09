package dev.opsmind.ticketworkflow.ticket.application.command;

import java.util.Objects;

/**
 * SPEC-TW-035 internal policy endpoint input. {@code caller} is the trusted,
 * JWT-derived internal principal invoking the policy check (never
 * client-suppliable — resolved by the controller exactly like {@code
 * SupportQueueAuthorizationController}/{@code SensitiveReadAuditPolicyController}'s
 * callers); {@code targetActorId}/{@code targetActorType}/{@code operation}
 * are the subject and operation the caller is asking the policy about,
 * carried in the request body per the API contract. {@code content} is the
 * free text to evaluate — an addition to the contract's shared shape,
 * necessary because this policy's entire purpose is scanning free text for
 * secret-like patterns; {@code null}/blank means there is nothing to scan
 * and the request is trivially allowed.
 */
public record EvaluateSecretDetectionCommand(
    ActorContext caller,
    String ticketId,
    String targetActorId,
    String targetActorType,
    String operation,
    String content,
    String correlationId,
    String traceId
) {

    public EvaluateSecretDetectionCommand {
        Objects.requireNonNull(caller, "caller must not be null");
        Objects.requireNonNull(ticketId, "ticketId must not be null");
        Objects.requireNonNull(targetActorId, "targetActorId must not be null");
        Objects.requireNonNull(targetActorType, "targetActorType must not be null");
        Objects.requireNonNull(operation, "operation must not be null");
    }
}
