package dev.opsmind.ticketworkflow.ticket.infrastructure.persistence.jpa.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(schema = "ticket", name = "sensitive_read_audit_decisions")
public class SensitiveReadAuditDecisionJpaEntity {

    @Id
    @Column(name = "id")
    private UUID id;

    @Column(name = "ticket_id")
    private String ticketId;

    @Column(name = "actor_id", nullable = false)
    private String actorId;

    @Column(name = "actor_type", nullable = false)
    private String actorType;

    @Column(name = "operation", nullable = false)
    private String operation;

    @Column(name = "decision", nullable = false)
    private String decision;

    @Column(name = "decision_code", nullable = false)
    private String decisionCode;

    @Column(name = "correlation_id")
    private String correlationId;

    @Column(name = "trace_id")
    private String traceId;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    protected SensitiveReadAuditDecisionJpaEntity() {
    }

    public SensitiveReadAuditDecisionJpaEntity(
        UUID id,
        String ticketId,
        String actorId,
        String actorType,
        String operation,
        String decision,
        String decisionCode,
        String correlationId,
        String traceId,
        Instant occurredAt
    ) {
        this.id = id;
        this.ticketId = ticketId;
        this.actorId = actorId;
        this.actorType = actorType;
        this.operation = operation;
        this.decision = decision;
        this.decisionCode = decisionCode;
        this.correlationId = correlationId;
        this.traceId = traceId;
        this.occurredAt = occurredAt;
    }

    public UUID getId() {
        return id;
    }

    public String getTicketId() {
        return ticketId;
    }

    public String getActorId() {
        return actorId;
    }

    public String getActorType() {
        return actorType;
    }

    public String getOperation() {
        return operation;
    }

    public String getDecision() {
        return decision;
    }

    public String getDecisionCode() {
        return decisionCode;
    }

    public String getCorrelationId() {
        return correlationId;
    }

    public String getTraceId() {
        return traceId;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }
}
