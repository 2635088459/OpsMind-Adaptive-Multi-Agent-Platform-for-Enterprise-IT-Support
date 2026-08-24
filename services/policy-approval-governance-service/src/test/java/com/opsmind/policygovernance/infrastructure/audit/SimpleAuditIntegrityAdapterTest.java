package com.opsmind.policygovernance.infrastructure.audit;

import com.opsmind.policygovernance.domain.audit.GovernanceAuditRecord;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("unit")
class SimpleAuditIntegrityAdapterTest {

    private final SimpleAuditIntegrityAdapter adapter = new SimpleAuditIntegrityAdapter();

    private GovernanceAuditRecord record(String reason) {
        return record(reason, null);
    }

    private GovernanceAuditRecord record(String reason, String previousHash) {
        return record(reason, previousHash, "ar-1");
    }

    private GovernanceAuditRecord record(String reason, String previousHash, String approvalRequestId) {
        return record(reason, previousHash, approvalRequestId, null);
    }

    private GovernanceAuditRecord record(String reason, String previousHash, String approvalRequestId, Instant archivedAt) {
        return new GovernanceAuditRecord(
            "audit-1", GovernanceAuditRecord.Action.APPROVAL_GRANTED, "actor-1", "tool-gateway",
            "src-req-1", "policy-1", "3", reason, "corr-1", "cause-1", null, Instant.parse("2026-01-01T00:00:00Z"), previousHash,
            "ticket-1", approvalRequestId, null, archivedAt
        );
    }

    @Test
    void isDeterministicForTheSameRecord() {
        GovernanceAuditRecord record = record("approved");
        assertThat(adapter.computeIntegrityHash(record)).isEqualTo(adapter.computeIntegrityHash(record));
    }

    @Test
    void changesWhenAFactFieldChanges() {
        String hashA = adapter.computeIntegrityHash(record("approved"));
        String hashB = adapter.computeIntegrityHash(record("denied"));
        assertThat(hashA).isNotEqualTo(hashB);
    }

    /**
     * SPEC-PG-017 (11-security §Tamper-Resistant Audit): {@code
     * previousHash} must participate in the hash — this is what turns a set
     * of independent per-record hashes into an actual chain, where altering
     * an earlier record's hash breaks every later link, not just that one
     * record's own fingerprint.
     */
    @Test
    void changesWhenThePreviousHashChanges() {
        String hashWithNoPredecessor = adapter.computeIntegrityHash(record("approved", null));
        String hashChainedToAPredecessor = adapter.computeIntegrityHash(record("approved", "some-prior-hash"));
        assertThat(hashWithNoPredecessor).isNotEqualTo(hashChainedToAPredecessor);
    }

    /** SPEC-PG-030: the new linkage fields are genuine fact fields — tampering with one must change the hash. */
    @Test
    void changesWhenALinkageFieldChanges() {
        String hashA = adapter.computeIntegrityHash(record("approved", null, "ar-1"));
        String hashB = adapter.computeIntegrityHash(record("approved", null, "ar-2"));
        assertThat(hashA).isNotEqualTo(hashB);
    }

    /**
     * SPEC-PG-031: unlike every other field, {@code archivedAt} must NOT
     * participate in the hash — see {@code domain.audit.GovernanceAuditRecord}'s
     * own javadoc for why a retention run archiving a record must not
     * retroactively look like tampering against a hash computed before the
     * record was ever archived.
     */
    @Test
    void doesNotChangeWhenArchivedAtChanges() {
        String hashBeforeArchiving = adapter.computeIntegrityHash(record("approved", null, "ar-1", null));
        String hashAfterArchiving = adapter.computeIntegrityHash(
            record("approved", null, "ar-1", Instant.parse("2026-06-01T00:00:00Z"))
        );
        assertThat(hashBeforeArchiving).isEqualTo(hashAfterArchiving);
    }
}
