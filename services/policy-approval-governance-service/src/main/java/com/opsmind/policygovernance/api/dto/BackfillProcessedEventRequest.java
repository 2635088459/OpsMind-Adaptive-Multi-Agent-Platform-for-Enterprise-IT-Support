package com.opsmind.policygovernance.api.dto;

import jakarta.validation.constraints.NotBlank;

/** SPEC-PG-034: mirrors {@link RequeueOutboxEventRequest}'s own reason-required shape for a state-mutating admin command. */
public record BackfillProcessedEventRequest(@NotBlank String reason) {
}
