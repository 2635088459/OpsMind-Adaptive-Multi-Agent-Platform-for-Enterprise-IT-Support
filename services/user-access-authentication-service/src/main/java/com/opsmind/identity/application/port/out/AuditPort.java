package com.opsmind.identity.application.port.out;

import com.opsmind.identity.domain.audit.IdentityAuditRecord;
import com.opsmind.identity.domain.shared.TenantId;

import java.util.List;
import java.util.Optional;

/** 13-package-and-class-design §Output Ports. INV-UA-006's write side. */
public interface AuditPort {

    IdentityAuditRecord record(IdentityAuditRecord record);

    List<IdentityAuditRecord> findByCorrelationId(String correlationId);

    /** SPEC-UA-031: the most recently persisted record's own {@code recordHash}, per tenant — the chain link a fresh write seals onto. Empty when this tenant has no prior audit record yet (the chain's own genesis case). */
    Optional<String> findMostRecentRecordHash(TenantId tenantId);
}
