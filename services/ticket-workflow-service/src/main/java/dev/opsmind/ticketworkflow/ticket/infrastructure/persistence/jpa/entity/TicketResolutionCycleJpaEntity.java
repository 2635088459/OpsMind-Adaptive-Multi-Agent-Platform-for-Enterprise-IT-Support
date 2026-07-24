package dev.opsmind.ticketworkflow.ticket.infrastructure.persistence.jpa.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(schema = "ticket", name = "ticket_resolution_cycles")
public class TicketResolutionCycleJpaEntity {

    @Id
    @Column(name = "resolution_cycle_id")
    private UUID resolutionCycleId;

    @Column(name = "ticket_id", nullable = false)
    private UUID ticketId;

    @Column(name = "cycle_number", nullable = false)
    private int cycleNumber;

    @Column(name = "cycle_status", nullable = false)
    private String cycleStatus;

    @Column(name = "opened_at", nullable = false)
    private Instant openedAt;

    protected TicketResolutionCycleJpaEntity() {
    }

    public TicketResolutionCycleJpaEntity(
        UUID resolutionCycleId,
        UUID ticketId,
        int cycleNumber,
        String cycleStatus,
        Instant openedAt
    ) {
        this.resolutionCycleId = resolutionCycleId;
        this.ticketId = ticketId;
        this.cycleNumber = cycleNumber;
        this.cycleStatus = cycleStatus;
        this.openedAt = openedAt;
    }

    public UUID getResolutionCycleId() {
        return resolutionCycleId;
    }

    public UUID getTicketId() {
        return ticketId;
    }

    public int getCycleNumber() {
        return cycleNumber;
    }

    public String getCycleStatus() {
        return cycleStatus;
    }

    public Instant getOpenedAt() {
        return openedAt;
    }
}
