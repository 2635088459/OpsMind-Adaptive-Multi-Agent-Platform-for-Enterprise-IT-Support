package dev.opsmind.ticketworkflow.ticket.infrastructure.persistence.jpa.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(schema = "ticket", name = "audit_records")
public class AuditRecordJpaEntity {

    @Id
    @Column(name = "audit_id")
    private UUID auditId;

    @Column(name = "audit_type", nullable = false)
    private String auditType;

    @Column(name = "action", nullable = false)
    private String action;

    @Column(name = "decision", nullable = false)
    private String decision;

    @Column(name = "actor_type", nullable = false)
    private String actorType;

    @Column(name = "actor_id", nullable = false)
    private String actorId;

    @Column(name = "client_id")
    private String clientId;

    @Column(name = "resource_type", nullable = false)
    private String resourceType;

    @Column(name = "resource_id", nullable = false)
    private String resourceId;

    @Column(name = "display_id")
    private String displayId;

    @Column(name = "ticket_status_before")
    private String ticketStatusBefore;

    @Column(name = "ticket_status_after")
    private String ticketStatusAfter;

    @Column(name = "trace_id")
    private String traceId;

    @Column(name = "command_id")
    private String commandId;

    @Column(name = "outcome", nullable = false)
    private String outcome;

    @Column(name = "data_classification", nullable = false)
    private String dataClassification;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    @Column(name = "view_type")
    private String viewType;

    @Column(name = "fields_policy_version")
    private String fieldsPolicyVersion;

    protected AuditRecordJpaEntity() {
    }

    public AuditRecordJpaEntity(
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
        this.auditId = auditId;
        this.auditType = auditType;
        this.action = action;
        this.decision = decision;
        this.actorType = actorType;
        this.actorId = actorId;
        this.clientId = clientId;
        this.resourceType = resourceType;
        this.resourceId = resourceId;
        this.displayId = displayId;
        this.ticketStatusBefore = ticketStatusBefore;
        this.ticketStatusAfter = ticketStatusAfter;
        this.traceId = traceId;
        this.commandId = commandId;
        this.outcome = outcome;
        this.dataClassification = dataClassification;
        this.occurredAt = occurredAt;
        this.viewType = viewType;
        this.fieldsPolicyVersion = fieldsPolicyVersion;
    }

    public UUID getAuditId() {
        return auditId;
    }

    public String getAuditType() {
        return auditType;
    }

    public String getAction() {
        return action;
    }

    public String getDecision() {
        return decision;
    }

    public String getActorType() {
        return actorType;
    }

    public String getActorId() {
        return actorId;
    }

    public String getClientId() {
        return clientId;
    }

    public String getResourceType() {
        return resourceType;
    }

    public String getResourceId() {
        return resourceId;
    }

    public String getDisplayId() {
        return displayId;
    }

    public String getTicketStatusBefore() {
        return ticketStatusBefore;
    }

    public String getTicketStatusAfter() {
        return ticketStatusAfter;
    }

    public String getTraceId() {
        return traceId;
    }

    public String getCommandId() {
        return commandId;
    }

    public String getOutcome() {
        return outcome;
    }

    public String getDataClassification() {
        return dataClassification;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }

    public String getViewType() {
        return viewType;
    }

    public String getFieldsPolicyVersion() {
        return fieldsPolicyVersion;
    }
}
