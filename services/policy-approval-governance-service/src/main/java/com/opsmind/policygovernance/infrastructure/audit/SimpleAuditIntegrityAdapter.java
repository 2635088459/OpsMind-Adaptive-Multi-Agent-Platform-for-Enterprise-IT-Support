package com.opsmind.policygovernance.infrastructure.audit;

import com.opsmind.policygovernance.application.port.AuditIntegrityPort;
import com.opsmind.policygovernance.domain.audit.GovernanceAuditRecord;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Computes a SHA-256 fingerprint over a {@link GovernanceAuditRecord}'s fact
 * fields so a later change to a "written" record is detectable — a canonical
 * string join keeps the hash deterministic across JVMs/serializers.
 * {@code integrityHash} itself is excluded so the hash never participates in
 * its own input. {@code previousHash} (SPEC-PG-017, 11-security
 * §Tamper-Resistant Audit) IS included — that is what turns a set of
 * independent per-record hashes into an actual chain: altering or deleting
 * an earlier record changes what {@code previousHash} the next record
 * should have had, breaking every link after it, not just the one record
 * that was touched.
 *
 * <p>SPEC-PG-030: {@code ticketId}/{@code approvalRequestId}/{@code
 * policyDecisionId} are included too — they are genuine fact fields on the
 * record (not incidental metadata), so leaving them out of the hash would
 * let someone tamper with a stored row's own linkage without invalidating
 * the chain, defeating the point of SPEC-PG-017's own guarantee.
 *
 * <p>SPEC-PG-031: {@code archivedAt} is deliberately NOT included, unlike
 * every other field above — see {@code domain.audit.GovernanceAuditRecord}'s
 * own javadoc for why: archiving is a retention-policy action on an
 * already-true fact, not a change to the fact itself, so it must not
 * retroactively look like tampering against a hash computed before the
 * record was ever archived.
 */
@Component
public class SimpleAuditIntegrityAdapter implements AuditIntegrityPort {

    @Override
    public String computeIntegrityHash(GovernanceAuditRecord record) {
        String canonical = String.join(
            "|",
            record.auditRecordId(),
            record.action().name(),
            record.actorId(),
            nullToEmpty(record.sourceDomain()),
            nullToEmpty(record.sourceRequestId()),
            nullToEmpty(record.policyId()),
            nullToEmpty(record.policyVersion()),
            record.reason(),
            record.correlationId(),
            nullToEmpty(record.causationId()),
            record.recordedAt().toString(),
            nullToEmpty(record.previousHash()),
            nullToEmpty(record.ticketId()),
            nullToEmpty(record.approvalRequestId()),
            nullToEmpty(record.policyDecisionId())
        );
        return sha256Hex(canonical);
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static String sha256Hex(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }
}
