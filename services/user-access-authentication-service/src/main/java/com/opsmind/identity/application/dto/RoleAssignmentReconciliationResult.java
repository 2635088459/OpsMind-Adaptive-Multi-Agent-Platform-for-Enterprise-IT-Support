package com.opsmind.identity.application.dto;

/** 03-state-machine's own time-driven edges: {@code PENDING --activate(validFrom)--> ACTIVE} and {@code ACTIVE --validUntil reached--> EXPIRED}. */
public record RoleAssignmentReconciliationResult(int activatedCount, int expiredCount) {
}
