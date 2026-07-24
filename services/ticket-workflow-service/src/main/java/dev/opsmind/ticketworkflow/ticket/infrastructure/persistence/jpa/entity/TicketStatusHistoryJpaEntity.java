package dev.opsmind.ticketworkflow.ticket.infrastructure.persistence.jpa.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(schema = "ticket", name = "ticket_status_history")
public class TicketStatusHistoryJpaEntity {

    @Id
    @Column(name = "history_id")
    private UUID historyId;

    @Column(name = "ticket_id", nullable = false)
    private UUID ticketId;

    @Column(name = "from_status")
    private String fromStatus;

    @Column(name = "to_status", nullable = false)
    private String toStatus;

    @Column(name = "transition_id", nullable = false)
    private String transitionId;

    @Column(name = "reason_code", nullable = false)
    private String reasonCode;

    @Column(name = "actor_type", nullable = false)
    private String actorType;

    @Column(name = "actor_id", nullable = false)
    private String actorId;

    @Column(name = "source_command_id")
    private String sourceCommandId;

    @Column(name = "source_event_id")
    private String sourceEventId;

    @Column(name = "workflow_id")
    private String workflowId;

    @Column(name = "aggregate_version", nullable = false)
    private long aggregateVersion;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    protected TicketStatusHistoryJpaEntity() {
    }

    public TicketStatusHistoryJpaEntity(
        UUID historyId,
        UUID ticketId,
        String fromStatus,
        String toStatus,
        String transitionId,
        String reasonCode,
        String actorType,
        String actorId,
        String sourceCommandId,
        String sourceEventId,
        String workflowId,
        long aggregateVersion,
        Instant occurredAt
    ) {
        this.historyId = historyId;
        this.ticketId = ticketId;
        this.fromStatus = fromStatus;
        this.toStatus = toStatus;
        this.transitionId = transitionId;
        this.reasonCode = reasonCode;
        this.actorType = actorType;
        this.actorId = actorId;
        this.sourceCommandId = sourceCommandId;
        this.sourceEventId = sourceEventId;
        this.workflowId = workflowId;
        this.aggregateVersion = aggregateVersion;
        this.occurredAt = occurredAt;
    }

    public UUID getHistoryId() {
        return historyId;
    }

    public UUID getTicketId() {
        return ticketId;
    }

    public String getFromStatus() {
        return fromStatus;
    }

    public String getToStatus() {
        return toStatus;
    }

    public String getTransitionId() {
        return transitionId;
    }

    public String getReasonCode() {
        return reasonCode;
    }

    public String getActorType() {
        return actorType;
    }

    public String getActorId() {
        return actorId;
    }

    public String getSourceCommandId() {
        return sourceCommandId;
    }

    public String getSourceEventId() {
        return sourceEventId;
    }

    public String getWorkflowId() {
        return workflowId;
    }

    public long getAggregateVersion() {
        return aggregateVersion;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }
}
