package com.opsmind.identity.application.query;

/**
 * SPEC-UA-031 (11-security: audit access is itself audited). {@code
 * tenantId}/{@code actorRef} identify the admin caller (for the self-audit
 * record this query itself produces) — {@code correlationId} is the target
 * being searched for, {@code requestCorrelationId} is this HTTP request's
 * own correlation id (07-data-model's {@code correlation_id} column is
 * never reused for two different logical purposes on the same record).
 */
public record QueryAuditRecordsByCorrelationIdQuery(
    String tenantId,
    String actorRef,
    String correlationId,
    String requestCorrelationId
) {
}
