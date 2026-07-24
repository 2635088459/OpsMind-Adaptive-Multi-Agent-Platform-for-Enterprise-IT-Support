package dev.opsmind.ticketworkflow.ticket.infrastructure.persistence.jpa.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(schema = "ticket", name = "tickets")
public class TicketJpaEntity {

    @Id
    @Column(name = "ticket_id")
    private UUID ticketId;

    @Column(name = "display_id", nullable = false, unique = true)
    private String displayId;

    @Column(name = "requester_id", nullable = false)
    private String requesterId;

    @Column(name = "title", nullable = false)
    private String title;

    @Column(name = "initial_description", nullable = false)
    private String initialDescription;

    @Column(name = "source", nullable = false)
    private String source;

    @Column(name = "application_code", nullable = false)
    private String applicationCode;

    @Column(name = "category")
    private String category;

    @Column(name = "subcategory")
    private String subcategory;

    @Column(name = "priority", nullable = false)
    private String priority;

    @Column(name = "status", nullable = false)
    private String status;

    @Column(name = "current_team_id")
    private String currentTeamId;

    @Column(name = "current_support_user_id")
    private String currentSupportUserId;

    @Column(name = "active_workflow_id")
    private String activeWorkflowId;

    @Column(name = "current_resolution_cycle_id", nullable = false)
    private UUID currentResolutionCycleId;

    @Column(name = "auto_close_due_at")
    private Instant autoCloseDueAt;

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    @Column(name = "closed_at")
    private Instant closedAt;

    @Column(name = "cancelled_at")
    private Instant cancelledAt;

    @Column(name = "cancel_reason_code")
    private String cancelReasonCode;

    @Column(name = "close_reason_code")
    private String closeReasonCode;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    @Column(name = "created_by_type", nullable = false)
    private String createdByType;

    @Column(name = "created_by_id", nullable = false)
    private String createdById;

    protected TicketJpaEntity() {
    }

    public TicketJpaEntity(
        UUID ticketId,
        String displayId,
        String requesterId,
        String title,
        String initialDescription,
        String source,
        String applicationCode,
        String category,
        String subcategory,
        String priority,
        String status,
        String currentTeamId,
        String currentSupportUserId,
        String activeWorkflowId,
        UUID currentResolutionCycleId,
        Instant autoCloseDueAt,
        Instant resolvedAt,
        Instant closedAt,
        Instant cancelledAt,
        String cancelReasonCode,
        String closeReasonCode,
        Instant createdAt,
        Instant updatedAt,
        long version,
        String createdByType,
        String createdById
    ) {
        this.ticketId = ticketId;
        this.displayId = displayId;
        this.requesterId = requesterId;
        this.title = title;
        this.initialDescription = initialDescription;
        this.source = source;
        this.applicationCode = applicationCode;
        this.category = category;
        this.subcategory = subcategory;
        this.priority = priority;
        this.status = status;
        this.currentTeamId = currentTeamId;
        this.currentSupportUserId = currentSupportUserId;
        this.activeWorkflowId = activeWorkflowId;
        this.currentResolutionCycleId = currentResolutionCycleId;
        this.autoCloseDueAt = autoCloseDueAt;
        this.resolvedAt = resolvedAt;
        this.closedAt = closedAt;
        this.cancelledAt = cancelledAt;
        this.cancelReasonCode = cancelReasonCode;
        this.closeReasonCode = closeReasonCode;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
        this.version = version;
        this.createdByType = createdByType;
        this.createdById = createdById;
    }

    public UUID getTicketId() {
        return ticketId;
    }

    public String getDisplayId() {
        return displayId;
    }

    public String getRequesterId() {
        return requesterId;
    }

    public String getTitle() {
        return title;
    }

    public String getInitialDescription() {
        return initialDescription;
    }

    public String getSource() {
        return source;
    }

    public String getApplicationCode() {
        return applicationCode;
    }

    public String getCategory() {
        return category;
    }

    public String getSubcategory() {
        return subcategory;
    }

    public String getPriority() {
        return priority;
    }

    public String getStatus() {
        return status;
    }

    public String getCurrentTeamId() {
        return currentTeamId;
    }

    public String getCurrentSupportUserId() {
        return currentSupportUserId;
    }

    public String getActiveWorkflowId() {
        return activeWorkflowId;
    }

    public UUID getCurrentResolutionCycleId() {
        return currentResolutionCycleId;
    }

    public Instant getAutoCloseDueAt() {
        return autoCloseDueAt;
    }

    public Instant getResolvedAt() {
        return resolvedAt;
    }

    public Instant getClosedAt() {
        return closedAt;
    }

    public Instant getCancelledAt() {
        return cancelledAt;
    }

    public String getCancelReasonCode() {
        return cancelReasonCode;
    }

    public String getCloseReasonCode() {
        return closeReasonCode;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public long getVersion() {
        return version;
    }

    public String getCreatedByType() {
        return createdByType;
    }

    public String getCreatedById() {
        return createdById;
    }
}
