package com.opsmind.identity.infrastructure.audit;

import com.opsmind.identity.application.port.out.AuditIntegrityPort;
import com.opsmind.identity.domain.audit.IdentityAuditRecord;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * SPEC-UA-031: real SHA-256 chain-link computation, mirroring
 * policy-approval-governance-service's own {@code SimpleAuditIntegrityAdapter}
 * (canonical pipe-joined fact fields, previous record's own hash folded in so
 * tampering with any earlier record changes every hash after it).
 */
@Component
public class Sha256AuditIntegrityAdapter implements AuditIntegrityPort {

    private static final String NULL_PLACEHOLDER = "-";

    @Override
    public String computeRecordHash(IdentityAuditRecord record) {
        String canonical = String.join(
            "|",
            record.auditId(),
            record.tenantId().value(),
            record.action().name(),
            field(record.actorRef()),
            field(record.subjectRef()),
            field(record.resourceRef()),
            record.outcome().name(),
            field(record.reasonCode()),
            record.correlationId().value(),
            record.occurredAt().toString(),
            field(record.previousHash())
        );
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashed = digest.digest(canonical.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hashed);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is a JDK-mandatory algorithm and must always be available", e);
        }
    }

    private static String field(String value) {
        return value == null ? NULL_PLACEHOLDER : value;
    }
}
