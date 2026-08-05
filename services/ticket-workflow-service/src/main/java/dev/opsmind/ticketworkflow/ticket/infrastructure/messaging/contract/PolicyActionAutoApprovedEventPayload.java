package dev.opsmind.ticketworkflow.ticket.infrastructure.messaging.contract;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.Instant;

/** 06-event-contracts CON-009 {@code policy.action_auto_approved} payload shape (SPEC-TW-018 asyncapi.yaml routing key: {@code policy.action-auto-approved.v1}). */
@JsonIgnoreProperties(ignoreUnknown = true)
public record PolicyActionAutoApprovedEventPayload(
    String workflowId,
    String actionId,
    String actionType,
    String riskLevel,
    String policyId,
    String policyVersion,
    String policyDecisionId,
    Instant decidedAt
) {
}
