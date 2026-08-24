package com.opsmind.policygovernance.infrastructure.messaging.contract;

import com.opsmind.policygovernance.domain.decision.Constraint;

import java.time.Instant;
import java.util.List;

/**
 * SPEC-PG-027: {@code ticket.approval.required.v1}'s own payload shape.
 * 06-event-contracts §Consumed Events names this event's purpose ("02
 * requests approval for closure override, escalation exception, or SLA
 * exception") — the same three-item list SPEC-PG-023 already gave its own
 * {@link com.opsmind.policygovernance.domain.approval.ApprovalType} values
 * to ({@code TICKET_SLA_EXCEPTION}/{@code TICKET_CLOSURE_OVERRIDE}/{@code
 * TICKET_ESCALATION_EXCEPTION}), but never gave a real caller. {@code
 * exceptionType} is this event's own discriminator — {@code
 * "SLA_EXCEPTION"}, {@code "CLOSURE_OVERRIDE"}, or {@code
 * "ESCALATION_EXCEPTION"} map to those three types; {@code null} (an
 * ordinary ticket action that is none of the three named exceptions) maps
 * to the generic {@code TICKET_ACTION} — see {@code
 * TicketApprovalRequiredEventMapper#resolveApprovalType}. Otherwise the
 * direct structural analog of {@link ToolApprovalRequiredPayload}, with
 * {@code ticketId} itself standing in as the primary business key (a
 * ticket-originated approval has no separate "tool request"/"workflow
 * instance" identifier the way those two events do).
 */
public record TicketApprovalRequiredPayload(
    String ticketId,
    String exceptionType,
    String riskLevel,
    String inputHash,
    List<Constraint> constraints,
    Instant expiresAt
) {
}
