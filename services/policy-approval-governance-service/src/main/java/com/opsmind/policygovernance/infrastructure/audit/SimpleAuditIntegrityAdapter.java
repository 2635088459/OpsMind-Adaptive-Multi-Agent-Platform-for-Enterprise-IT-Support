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
 * its own input.
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
            record.recordedAt().toString()
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
