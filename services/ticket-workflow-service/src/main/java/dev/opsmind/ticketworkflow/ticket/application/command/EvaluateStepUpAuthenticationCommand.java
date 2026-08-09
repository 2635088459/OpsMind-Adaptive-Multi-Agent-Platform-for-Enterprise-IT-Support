package dev.opsmind.ticketworkflow.ticket.application.command;

import java.util.Objects;

/**
 * SPEC-TW-036 internal policy endpoint input. {@code caller} is the trusted,
 * JWT-derived internal principal invoking the policy check (never
 * client-suppliable — resolved by the controller exactly like {@code
 * SupportQueueAuthorizationController}/{@code
 * SensitiveReadAuditPolicyController}/{@code SecretDetectionController}'s
 * callers); {@code targetActorId}/{@code targetActorType}/{@code operation}
 * are the subject and operation the caller is asking the policy about,
 * carried in the request body per the API contract. {@code proof} is the
 * step-up evidence to evaluate — an addition to the contract's shared
 * shape, necessary because the caller (a trusted internal service) and the
 * proof's subject (the human/service that completed step-up) are different
 * principals here, unlike {@code CancelTicketCommand}/{@code
 * EscalateTicketCommand} where the actor's own JWT already carries it.
 * {@code null} means no proof was supplied.
 */
public record EvaluateStepUpAuthenticationCommand(
    ActorContext caller,
    String ticketId,
    String targetActorId,
    String targetActorType,
    String operation,
    StepUpProof proof,
    String correlationId,
    String traceId
) {

    public EvaluateStepUpAuthenticationCommand {
        Objects.requireNonNull(caller, "caller must not be null");
        Objects.requireNonNull(ticketId, "ticketId must not be null");
        Objects.requireNonNull(targetActorId, "targetActorId must not be null");
        Objects.requireNonNull(targetActorType, "targetActorType must not be null");
        Objects.requireNonNull(operation, "operation must not be null");
    }
}
