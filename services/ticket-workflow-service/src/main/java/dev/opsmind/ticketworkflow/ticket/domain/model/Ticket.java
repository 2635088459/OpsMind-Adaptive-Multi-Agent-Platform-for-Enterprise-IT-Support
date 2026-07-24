package dev.opsmind.ticketworkflow.ticket.domain.model;

import dev.opsmind.ticketworkflow.ticket.domain.event.TicketCreated;
import dev.opsmind.ticketworkflow.ticket.domain.event.TicketDomainEvent;
import dev.opsmind.ticketworkflow.ticket.domain.value.ApplicationCode;
import dev.opsmind.ticketworkflow.ticket.domain.value.RequesterId;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketDescription;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketDisplayId;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketId;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketPriority;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketSource;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketStatus;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketTitle;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public final class Ticket {

    private final TicketId id;
    private final TicketDisplayId displayId;
    private final RequesterId requesterId;
    private final TicketTitle title;
    private final TicketDescription description;
    private final ApplicationCode applicationCode;
    private final TicketSource source;
    private final String category;
    private final String subcategory;
    private final TicketPriority priority;
    private final TicketStatus status;
    private final String currentTeamId;
    private final String currentSupportUserId;
    private final String activeWorkflowId;
    private final UUID currentResolutionCycleId;
    private final Instant autoCloseDueAt;
    private final Instant resolvedAt;
    private final Instant closedAt;
    private final Instant cancelledAt;
    private final String cancelReasonCode;
    private final String closeReasonCode;
    private final Instant createdAt;
    private final Instant updatedAt;
    private final long version;
    private final String createdByType;
    private final String createdById;
    private final List<TicketDomainEvent> domainEvents = new ArrayList<>();

    private Ticket(
        TicketId id,
        TicketDisplayId displayId,
        RequesterId requesterId,
        TicketTitle title,
        TicketDescription description,
        ApplicationCode applicationCode,
        TicketSource source,
        UUID currentResolutionCycleId,
        Instant createdAt,
        Instant updatedAt,
        long version
    ) {
        this.id = id;
        this.displayId = displayId;
        this.requesterId = requesterId;
        this.title = title;
        this.description = description;
        this.applicationCode = applicationCode;
        this.source = source;
        this.category = null;
        this.subcategory = null;
        this.priority = TicketPriority.UNASSIGNED;
        this.status = TicketStatus.NEW;
        this.currentTeamId = null;
        this.currentSupportUserId = null;
        this.activeWorkflowId = null;
        this.currentResolutionCycleId = currentResolutionCycleId;
        this.autoCloseDueAt = null;
        this.resolvedAt = null;
        this.closedAt = null;
        this.cancelledAt = null;
        this.cancelReasonCode = null;
        this.closeReasonCode = null;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
        this.version = version;
        this.createdByType = "EMPLOYEE";
        this.createdById = requesterId.value();
    }

    public static Ticket create(
        TicketId id,
        TicketDisplayId displayId,
        RequesterId requesterId,
        TicketTitle title,
        TicketDescription description,
        ApplicationCode applicationCode,
        TicketSource source,
        UUID initialResolutionCycleId,
        Instant now
    ) {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(displayId, "displayId must not be null");
        Objects.requireNonNull(requesterId, "requesterId must not be null");
        Objects.requireNonNull(title, "title must not be null");
        Objects.requireNonNull(description, "description must not be null");
        Objects.requireNonNull(applicationCode, "applicationCode must not be null");
        Objects.requireNonNull(source, "source must not be null");
        Objects.requireNonNull(initialResolutionCycleId, "initialResolutionCycleId must not be null");
        Objects.requireNonNull(now, "now must not be null");

        Ticket ticket = new Ticket(
            id,
            displayId,
            requesterId,
            title,
            description,
            applicationCode,
            source,
            initialResolutionCycleId,
            now,
            now,
            0L
        );

        ticket.domainEvents.add(new TicketCreated(
            id,
            displayId,
            requesterId,
            applicationCode,
            source,
            0L,
            now
        ));

        return ticket;
    }

    public List<TicketDomainEvent> pullDomainEvents() {
        List<TicketDomainEvent> events = List.copyOf(domainEvents);
        domainEvents.clear();
        return events;
    }

    public TicketId id() {
        return id;
    }

    public TicketDisplayId displayId() {
        return displayId;
    }

    public RequesterId requesterId() {
        return requesterId;
    }

    public TicketTitle title() {
        return title;
    }

    public TicketDescription description() {
        return description;
    }

    public ApplicationCode applicationCode() {
        return applicationCode;
    }

    public TicketSource source() {
        return source;
    }

    public String category() {
        return category;
    }

    public String subcategory() {
        return subcategory;
    }

    public TicketPriority priority() {
        return priority;
    }

    public TicketStatus status() {
        return status;
    }

    public String currentTeamId() {
        return currentTeamId;
    }

    public String currentSupportUserId() {
        return currentSupportUserId;
    }

    public String activeWorkflowId() {
        return activeWorkflowId;
    }

    public UUID currentResolutionCycleId() {
        return currentResolutionCycleId;
    }

    public Instant autoCloseDueAt() {
        return autoCloseDueAt;
    }

    public Instant resolvedAt() {
        return resolvedAt;
    }

    public Instant closedAt() {
        return closedAt;
    }

    public Instant cancelledAt() {
        return cancelledAt;
    }

    public String cancelReasonCode() {
        return cancelReasonCode;
    }

    public String closeReasonCode() {
        return closeReasonCode;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant updatedAt() {
        return updatedAt;
    }

    public long version() {
        return version;
    }

    public String createdByType() {
        return createdByType;
    }

    public String createdById() {
        return createdById;
    }
}
