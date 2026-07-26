package dev.opsmind.ticketworkflow.ticket.application.model;

import java.time.Instant;
import java.util.UUID;

public record AuditRecordEntry(
    UUID auditId,
    String auditType,
    String action,
    String decision,
    String actorType,
    String actorId,
    String clientId,
    String resourceType,
    String resourceId,
    String displayId,
    String ticketStatusBefore,
    String ticketStatusAfter,
    String traceId,
    String commandId,
    String outcome,
    String dataClassification,
    Instant occurredAt,
    String viewType,
    String fieldsPolicyVersion
) {
}
