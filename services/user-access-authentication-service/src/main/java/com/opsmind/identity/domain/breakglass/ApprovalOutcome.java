package com.opsmind.identity.domain.breakglass;

/**
 * SPEC-UA-028 (Identity Lifecycle Events — 06-event-contracts §Consumed
 * events: "Domain 06: approval or break-glass approved/denied/expired
 * facts for controlled privileged flows"). The three real outcomes
 * policy-approval-governance-service's own {@code approval.granted.v1}/
 * {@code approval.denied.v1}/{@code approval.expired.v1} events carry.
 */
public enum ApprovalOutcome {
    GRANTED,
    DENIED,
    EXPIRED
}
