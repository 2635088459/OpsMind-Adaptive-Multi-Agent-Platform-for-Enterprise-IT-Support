package com.opsmind.policygovernance.domain.approval;

/**
 * What kind of downstream action an {@link ApprovalRequest} gates
 * (04-use-cases §UC-PG-002 — 05 Tool Gateway / 02 Ticket / 03 Runtime submit
 * approval requests). {@code POLICY_OVERRIDE} is the high-risk override
 * path (04-use-cases §UC-PG-006, 03-state-machine §Override State Machine).
 */
public enum ApprovalType {
    TOOL_EXECUTION,
    TICKET_ACTION,
    WORKFLOW_ACTION,
    POLICY_OVERRIDE,
    GENERIC
}
