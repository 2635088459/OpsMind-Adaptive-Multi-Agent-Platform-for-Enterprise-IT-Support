package com.opsmind.identity.application.port.out;

import com.opsmind.identity.domain.audit.IdentityAuditRecord;

/**
 * SPEC-UA-031 (07-data-model §identity_audit_records: {@code previous_hash},
 * {@code record_hash}). Computes one record's own fingerprint over its fact
 * fields — kept as its own port (not folded into {@link AuditPort}) so the
 * pure hashing algorithm is unit-testable without a database, mirroring
 * policy-approval-governance-service's own identically-purposed {@code
 * AuditIntegrityPort}/{@code SimpleAuditIntegrityAdapter} split.
 */
public interface AuditIntegrityPort {

    /** {@code record.previousHash()} must already be set (the immediately preceding record's own {@code recordHash}) — that value is itself part of what gets hashed, chaining the two. */
    String computeRecordHash(IdentityAuditRecord record);
}
