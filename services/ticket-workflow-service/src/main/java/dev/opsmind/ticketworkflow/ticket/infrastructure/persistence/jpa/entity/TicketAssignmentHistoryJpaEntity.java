package dev.opsmind.ticketworkflow.ticket.infrastructure.persistence.jpa.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(schema = "ticket", name = "ticket_assignment_history")
public class TicketAssignmentHistoryJpaEntity {

    @Id
    @Column(name = "assignment_history_id")
    private UUID assignmentHistoryId;

    @Column(name = "ticket_id", nullable = false)
    private UUID ticketId;

    @Column(name = "action", nullable = false)
    private String action;

    @Column(name = "previous_assignee_id")
    private String previousAssigneeId;

    @Column(name = "new_assignee_id")
    private String newAssigneeId;

    @Column(name = "previous_status", nullable = false)
    private String previousStatus;

    @Column(name = "new_status", nullable = false)
    private String newStatus;

    @Column(name = "actor_type", nullable = false)
    private String actorType;

    @Column(name = "actor_id", nullable = false)
    private String actorId;

    @Column(name = "reason", nullable = false)
    private String reason;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    @Column(name = "correlation_id")
    private String correlationId;

    @Column(name = "causation_id")
    private String causationId;

    @Column(name = "resulting_version", nullable = false)
    private long resultingVersion;

    protected TicketAssignmentHistoryJpaEntity() {
    }

    public TicketAssignmentHistoryJpaEntity(
        UUID assignmentHistoryId,
        UUID ticketId,
        String action,
        String previousAssigneeId,
        String newAssigneeId,
        String previousStatus,
        String newStatus,
        String actorType,
        String actorId,
        String reason,
        Instant occurredAt,
        String correlationId,
        String causationId,
        long resultingVersion
    ) {
        this.assignmentHistoryId = assignmentHistoryId;
        this.ticketId = ticketId;
        this.action = action;
        this.previousAssigneeId = previousAssigneeId;
        this.newAssigneeId = newAssigneeId;
        this.previousStatus = previousStatus;
        this.newStatus = newStatus;
        this.actorType = actorType;
        this.actorId = actorId;
        this.reason = reason;
        this.occurredAt = occurredAt;
        this.correlationId = correlationId;
        this.causationId = causationId;
        this.resultingVersion = resultingVersion;
    }

    public UUID getAssignmentHistoryId() {
        return assignmentHistoryId;
    }

    public UUID getTicketId() {
        return ticketId;
    }

    public String getAction() {
        return action;
    }

    public String getPreviousAssigneeId() {
        return previousAssigneeId;
    }

    public String getNewAssigneeId() {
        return newAssigneeId;
    }

    public String getPreviousStatus() {
        return previousStatus;
    }

    public String getNewStatus() {
        return newStatus;
    }

    public String getActorType() {
        return actorType;
    }

    public String getActorId() {
        return actorId;
    }

    public String getReason() {
        return reason;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }

    public String getCorrelationId() {
        return correlationId;
    }

    public String getCausationId() {
        return causationId;
    }

    public long getResultingVersion() {
        return resultingVersion;
    }
}
