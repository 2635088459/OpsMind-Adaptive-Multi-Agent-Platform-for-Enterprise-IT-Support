package com.opsmind.identity.infrastructure.audit;

import com.opsmind.identity.domain.audit.AuditOutcome;
import com.opsmind.identity.domain.audit.IdentityAuditAction;
import com.opsmind.identity.domain.audit.IdentityAuditRecord;
import com.opsmind.identity.domain.shared.CorrelationId;
import com.opsmind.identity.domain.shared.TenantId;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/** SPEC-UA-031 (07-data-model §identity_audit_records). */
class Sha256AuditIntegrityAdapterTest {

    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
    private final Sha256AuditIntegrityAdapter adapter = new Sha256AuditIntegrityAdapter();

    private IdentityAuditRecord baseRecord() {
        return IdentityAuditRecord.record(
            "audit-1", new TenantId("tenant-1"), IdentityAuditAction.USER_IDENTITY_LINKED, "actor-1", "subject-1", null,
            AuditOutcome.SUCCESS, null, new CorrelationId("corr-1"), NOW
        );
    }

    @Test
    void computesADeterministic64CharacterHexDigest() {
        String hash = adapter.computeRecordHash(baseRecord());
        assertThat(hash).hasSize(64).matches("[0-9a-f]+");
        assertThat(adapter.computeRecordHash(baseRecord())).isEqualTo(hash);
    }

    @Test
    void changingAnyFactFieldChangesTheHash() {
        String original = adapter.computeRecordHash(baseRecord());
        IdentityAuditRecord differentOutcome = IdentityAuditRecord.record(
            "audit-1", new TenantId("tenant-1"), IdentityAuditAction.USER_IDENTITY_LINKED, "actor-1", "subject-1", null,
            AuditOutcome.DENIED, null, new CorrelationId("corr-1"), NOW
        );
        assertThat(adapter.computeRecordHash(differentOutcome)).isNotEqualTo(original);
    }

    @Test
    void chainingOntoADifferentPreviousHashChangesTheResult() {
        IdentityAuditRecord sealedOntoGenesis = baseRecord().withHashes(null, null);
        IdentityAuditRecord sealedOntoPriorRecord = baseRecord().withHashes("some-prior-hash", null);

        assertThat(adapter.computeRecordHash(sealedOntoGenesis)).isNotEqualTo(adapter.computeRecordHash(sealedOntoPriorRecord));
    }
}
