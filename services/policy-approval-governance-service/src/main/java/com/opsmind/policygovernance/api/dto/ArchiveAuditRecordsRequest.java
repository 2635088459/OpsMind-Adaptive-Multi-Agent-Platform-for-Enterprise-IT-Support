package com.opsmind.policygovernance.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;

/**
 * SPEC-PG-031 (11-security §Tamper-Resistant Audit: "may only be archived by
 * retention policy"). Mirrors {@link RequeueOutboxEventRequest}'s own
 * reason-required shape for a state-mutating admin command. No fixed
 * retention duration is hardcoded anywhere in this service — the caller
 * (an operator, or an external scheduler acting on a configured policy)
 * supplies it explicitly each time, the same "no invented business rule"
 * shape {@code OutboxAdminService#requeue} already uses for its own reason.
 */
public record ArchiveAuditRecordsRequest(@Positive int retentionDays, @NotBlank String reason) {
}
