package com.opsmind.identity.application.port.in;

import com.opsmind.identity.application.query.QueryAuditRecordsByCorrelationIdQuery;
import com.opsmind.identity.domain.audit.IdentityAuditRecord;

import java.util.List;

/** SPEC-UA-031 (11-security: audit access is itself audited). */
public interface QueryAuditRecordsUseCase {

    List<IdentityAuditRecord> findByCorrelationId(QueryAuditRecordsByCorrelationIdQuery query);
}
