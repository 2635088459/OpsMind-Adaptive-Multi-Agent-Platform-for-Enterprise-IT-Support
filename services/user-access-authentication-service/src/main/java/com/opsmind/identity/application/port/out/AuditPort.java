package com.opsmind.identity.application.port.out;

import com.opsmind.identity.domain.audit.IdentityAuditRecord;

import java.util.List;

/** 13-package-and-class-design §Output Ports. INV-UA-006's write side. */
public interface AuditPort {

    IdentityAuditRecord record(IdentityAuditRecord record);

    List<IdentityAuditRecord> findByCorrelationId(String correlationId);
}
