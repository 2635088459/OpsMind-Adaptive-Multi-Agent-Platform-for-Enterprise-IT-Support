package com.opsmind.identity.infrastructure.messaging.contract;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * SPEC-UA-028. The one field of policy-approval-governance-service's own
 * real {@code approval.granted.v1}/{@code approval.denied.v1}/{@code
 * approval.expired.v1} payload (verified directly against that service's
 * own {@code ApprovalGrantedEvent}/{@code ApprovalDeniedEvent}/{@code
 * ApprovalExpiredEvent}) this domain actually needs: {@code
 * approvalRequestId}, the exact value a caller asserts as a {@code
 * BreakGlassGrant#approvalReference} when activating break-glass access —
 * the only field this consumer correlates on. {@code decidedBy}/{@code
 * reason}/{@code requestKey}/{@code sourceDomain}/... exist on the real
 * wire payload but not on all three event types, so deliberately not
 * declared here rather than assuming a shape that does not hold across
 * every one — {@code @JsonIgnoreProperties(ignoreUnknown = true)} is
 * required, not optional: this app's own Jackson configuration defaults
 * {@code FAIL_ON_UNKNOWN_PROPERTIES} to {@code true} (confirmed the hard
 * way, via a real Testcontainers RabbitMQ round trip — a bare {@code
 * new ObjectMapper()} misled an earlier version of this test into
 * assuming the opposite).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ApprovalDecisionPayload(
    String approvalRequestId
) {
}
