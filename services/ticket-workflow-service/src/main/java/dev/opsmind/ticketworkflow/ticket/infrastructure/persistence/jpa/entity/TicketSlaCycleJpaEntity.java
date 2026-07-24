package dev.opsmind.ticketworkflow.ticket.infrastructure.persistence.jpa.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(schema = "ticket", name = "ticket_sla_cycles")
public class TicketSlaCycleJpaEntity {

    @Id
    @Column(name = "sla_cycle_id")
    private UUID slaCycleId;

    @Column(name = "ticket_id", nullable = false)
    private UUID ticketId;

    @Column(name = "resolution_cycle_id", nullable = false)
    private UUID resolutionCycleId;

    @Column(name = "policy_id", nullable = false)
    private String policyId;

    @Column(name = "cycle_number", nullable = false)
    private int cycleNumber;

    @Column(name = "status", nullable = false)
    private String status;

    @Column(name = "response_due_at")
    private Instant responseDueAt;

    @Column(name = "resolution_due_at")
    private Instant resolutionDueAt;

    @Column(name = "accumulated_paused_seconds", nullable = false)
    private long accumulatedPausedSeconds;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    protected TicketSlaCycleJpaEntity() {
    }

    public TicketSlaCycleJpaEntity(
        UUID slaCycleId,
        UUID ticketId,
        UUID resolutionCycleId,
        String policyId,
        int cycleNumber,
        String status,
        Instant responseDueAt,
        Instant resolutionDueAt,
        long accumulatedPausedSeconds,
        Instant createdAt,
        Instant updatedAt,
        long version
    ) {
        this.slaCycleId = slaCycleId;
        this.ticketId = ticketId;
        this.resolutionCycleId = resolutionCycleId;
        this.policyId = policyId;
        this.cycleNumber = cycleNumber;
        this.status = status;
        this.responseDueAt = responseDueAt;
        this.resolutionDueAt = resolutionDueAt;
        this.accumulatedPausedSeconds = accumulatedPausedSeconds;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
        this.version = version;
    }

    public UUID getSlaCycleId() {
        return slaCycleId;
    }

    public UUID getTicketId() {
        return ticketId;
    }

    public UUID getResolutionCycleId() {
        return resolutionCycleId;
    }

    public String getPolicyId() {
        return policyId;
    }

    public int getCycleNumber() {
        return cycleNumber;
    }

    public String getStatus() {
        return status;
    }

    public Instant getResponseDueAt() {
        return responseDueAt;
    }

    public Instant getResolutionDueAt() {
        return resolutionDueAt;
    }

    public long getAccumulatedPausedSeconds() {
        return accumulatedPausedSeconds;
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
}
