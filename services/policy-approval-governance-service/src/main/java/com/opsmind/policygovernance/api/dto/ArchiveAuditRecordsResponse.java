package com.opsmind.policygovernance.api.dto;

/** SPEC-PG-031: response shape for {@code POST /api/v1/admin/governance-audit:archive}. */
public record ArchiveAuditRecordsResponse(int archivedCount) {
}
