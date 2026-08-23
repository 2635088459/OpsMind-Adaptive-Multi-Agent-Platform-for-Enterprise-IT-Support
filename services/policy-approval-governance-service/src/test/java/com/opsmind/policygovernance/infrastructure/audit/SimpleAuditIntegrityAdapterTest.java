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
        return new GovernanceAuditRecord(
            "audit-1", GovernanceAuditRecord.Action.APPROVAL_GRANTED, "actor-1", "tool-gateway",
            "src-req-1", "policy-1", "3", reason, "corr-1", "cause-1", null, Instant.parse("2026-01-01T00:00:00Z")
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
}
