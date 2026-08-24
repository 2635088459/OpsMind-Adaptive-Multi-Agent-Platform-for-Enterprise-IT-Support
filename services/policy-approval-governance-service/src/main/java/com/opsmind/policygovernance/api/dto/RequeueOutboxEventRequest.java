package com.opsmind.policygovernance.api.dto;

import jakarta.validation.constraints.NotBlank;

/** SPEC-PG-024: mirrors {@link CancelApprovalRequest}'s reason-required shape for any state-mutating admin command. */
public record RequeueOutboxEventRequest(@NotBlank String reason) {
}
