package com.opsmind.policygovernance.domain.approval;

/**
 * What kind of downstream action an {@link ApprovalRequest} gates
 * (04-use-cases §UC-PG-002 — 05 Tool Gateway / 02 Ticket / 03 Runtime submit
 * approval requests). {@code POLICY_OVERRIDE} is the high-risk override
 * path (04-use-cases §UC-PG-006, 03-state-machine §Override State Machine).
 *
 * <p>{@code TICKET_SLA_EXCEPTION}/{@code TICKET_CLOSURE_OVERRIDE}/{@code
 * TICKET_ESCALATION_EXCEPTION} are SPEC-PG-023's own addition
 * (04-use-cases, 06-event-contracts §{@code ticket.approval.required.v1}:
 * "02 requests approval for closure override, escalation exception, or SLA
 * exception") — three distinct sub-kinds of ticket-originated approval,
 * split out from the generic {@code TICKET_ACTION} bucket so audit,
 * metrics, and downstream RBAC can tell them apart the same way {@code
 * POLICY_OVERRIDE} is already distinct from an ordinary {@code
 * TOOL_EXECUTION}. {@code TICKET_ACTION} itself is unchanged and still
 * covers every other ticket-originated approval that is none of these
 * three named exceptions. Every one of the three flows through the exact
 * same {@code ApprovalRequest}/{@code ApprovalService} request/grant/deny/
 * cancel machinery every other type already uses — 06-event-contracts names
 * no distinct state machine or event for them, only the classification
 * itself.
 */
public enum ApprovalType {
    TOOL_EXECUTION,
    TICKET_ACTION,
    WORKFLOW_ACTION,
    POLICY_OVERRIDE,
    TICKET_SLA_EXCEPTION,
    TICKET_CLOSURE_OVERRIDE,
    TICKET_ESCALATION_EXCEPTION,
    GENERIC
}
