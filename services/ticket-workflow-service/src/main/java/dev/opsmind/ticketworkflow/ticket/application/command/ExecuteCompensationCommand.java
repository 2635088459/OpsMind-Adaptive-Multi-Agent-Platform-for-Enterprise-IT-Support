package dev.opsmind.ticketworkflow.ticket.application.command;

import dev.opsmind.ticketworkflow.ticket.domain.value.CompensationAction;
import dev.opsmind.ticketworkflow.ticket.domain.value.ReconciliationReasonCode;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketId;

import java.time.Instant;

/**
 * SPEC-TW-040 api-contract §"Request", extended with {@code
 * compensationAction} beyond the shared Phase 10 recovery-request template
 * (mirrors {@code OpenReconciliationCaseCommand}, SPEC-TW-037, for {@code
 * reasonCode}/{@code reason}/{@code sourceReference}): domain-rules
 * "Compensation must select a defined action" requires one, unlike
 * SPEC-TW-037/038/039, which have no comparable selection to make.
 */
public record ExecuteCompensationCommand(
    TicketId ticketId,
    CompensationAction compensationAction,
    ReconciliationReasonCode reasonCode,
    String reason,
    String sourceReference,
    ActorContext actor,
    String idempotencyKey,
    String correlationId,
    String commandId,
    Instant requestedAt
) {
}
