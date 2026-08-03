package dev.opsmind.ticketworkflow.ticket.domain.model;

import dev.opsmind.ticketworkflow.ticket.domain.event.TicketApprovalGrantedApplied;
import dev.opsmind.ticketworkflow.ticket.domain.event.TicketApprovalWaitStarted;
import dev.opsmind.ticketworkflow.ticket.domain.event.TicketAssigned;
import dev.opsmind.ticketworkflow.ticket.domain.event.TicketClosed;
import dev.opsmind.ticketworkflow.ticket.domain.event.TicketCreated;
import dev.opsmind.ticketworkflow.ticket.domain.event.TicketDomainEvent;
import dev.opsmind.ticketworkflow.ticket.domain.event.TicketReassigned;
import dev.opsmind.ticketworkflow.ticket.domain.event.TicketReopened;
import dev.opsmind.ticketworkflow.ticket.domain.event.TicketResolved;
import dev.opsmind.ticketworkflow.ticket.domain.event.TicketStatusChanged;
import dev.opsmind.ticketworkflow.ticket.domain.event.TicketTriaged;
import dev.opsmind.ticketworkflow.ticket.domain.event.TicketUnassigned;
import dev.opsmind.ticketworkflow.ticket.domain.event.TicketUserInputRequested;
import dev.opsmind.ticketworkflow.ticket.domain.event.TicketUserInputResumed;
import dev.opsmind.ticketworkflow.ticket.domain.exception.InvalidStatusTransitionException;
import dev.opsmind.ticketworkflow.ticket.domain.exception.InvalidTicketStateException;
import dev.opsmind.ticketworkflow.ticket.domain.exception.InvalidTicketTransitionException;
import dev.opsmind.ticketworkflow.ticket.domain.exception.ReassignmentRequiresDifferentAssigneeException;
import dev.opsmind.ticketworkflow.ticket.domain.exception.TicketAlreadyAssignedException;
import dev.opsmind.ticketworkflow.ticket.domain.exception.TicketNotAssignedException;
import dev.opsmind.ticketworkflow.ticket.domain.value.ApplicationCode;
import dev.opsmind.ticketworkflow.ticket.domain.value.ApprovalRiskLevel;
import dev.opsmind.ticketworkflow.ticket.domain.value.CloseReasonCode;
import dev.opsmind.ticketworkflow.ticket.domain.value.OwnershipStatus;
import dev.opsmind.ticketworkflow.ticket.domain.value.ReopenReasonCode;
import dev.opsmind.ticketworkflow.ticket.domain.value.RequesterId;
import dev.opsmind.ticketworkflow.ticket.domain.value.ResolutionCode;
import dev.opsmind.ticketworkflow.ticket.domain.value.SupportQueueId;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketCategoryId;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketDescription;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketDisplayId;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketId;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketPriority;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketSource;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketStatus;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketSubcategoryId;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketTitle;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
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

    /**
     * SPEC-TW-007 §2: {@code OPEN → TRIAGED} ("OPEN" is this codebase's
     * {@link TicketStatus#NEW}, see {@link TicketStatus}'s Javadoc).
     * Write commands in this codebase never rehydrate a full {@link
     * Ticket} instance from storage (the guard-projection pattern
     * established by SPEC-TW-004 loads only the fields a command needs) —
     * this is therefore a stateless rule over the caller-supplied current
     * status/version rather than an instance method mutating a loaded
     * aggregate, but it stays on {@code Ticket} because the rule belongs
     * to the Ticket aggregate's contract, not to any one command's
     * application service. Catalog existence/active checks and queue
     * authorization are the caller's responsibility (they need I/O this
     * pure method must not perform); this method only enforces the state
     * guard and produces the event the caller persists.
     */
    public static TicketTriaged triage(
        TicketId ticketId,
        TicketStatus currentStatus,
        long currentVersion,
        TicketCategoryId categoryId,
        TicketSubcategoryId subcategoryId,
        TicketPriority priority,
        SupportQueueId supportQueueId,
        String triagedByActorType,
        String triagedByActorId,
        Instant occurredAt
    ) {
        Objects.requireNonNull(ticketId, "ticketId must not be null");
        Objects.requireNonNull(currentStatus, "currentStatus must not be null");
        Objects.requireNonNull(categoryId, "categoryId must not be null");
        Objects.requireNonNull(priority, "priority must not be null");
        Objects.requireNonNull(supportQueueId, "supportQueueId must not be null");
        Objects.requireNonNull(triagedByActorType, "triagedByActorType must not be null");
        Objects.requireNonNull(triagedByActorId, "triagedByActorId must not be null");
        Objects.requireNonNull(occurredAt, "occurredAt must not be null");
        if (priority == TicketPriority.UNASSIGNED) {
            throw new IllegalArgumentException("priority must be an explicit value, not UNASSIGNED");
        }
        if (currentStatus != TicketStatus.NEW) {
            throw new InvalidTicketTransitionException(currentStatus, TicketStatus.NEW);
        }

        return new TicketTriaged(
            ticketId,
            currentStatus,
            TicketStatus.TRIAGED,
            categoryId,
            subcategoryId,
            priority,
            supportQueueId,
            triagedByActorType,
            triagedByActorId,
            currentVersion + 1,
            occurredAt
        );
    }

    public static final Set<TicketStatus> REASSIGNABLE_STATUSES = Set.of(
        TicketStatus.ASSIGNED, TicketStatus.IN_PROGRESS, TicketStatus.WAITING_FOR_USER, TicketStatus.WAITING_FOR_APPROVAL
    );

    /** SPEC-TW-008 §2/§3: {@code TRIAGED, no assignee → ASSIGNED, assignee set}. */
    public static TicketAssigned assign(
        TicketId ticketId,
        TicketStatus currentStatus,
        String currentAssigneeId,
        long currentVersion,
        String assigneeId,
        String actorType,
        String actorId,
        String reason,
        Instant occurredAt
    ) {
        Objects.requireNonNull(ticketId, "ticketId must not be null");
        Objects.requireNonNull(currentStatus, "currentStatus must not be null");
        Objects.requireNonNull(assigneeId, "assigneeId must not be null");
        Objects.requireNonNull(actorType, "actorType must not be null");
        Objects.requireNonNull(actorId, "actorId must not be null");
        Objects.requireNonNull(reason, "reason must not be null");
        Objects.requireNonNull(occurredAt, "occurredAt must not be null");
        if (currentStatus != TicketStatus.TRIAGED) {
            throw new InvalidTicketTransitionException(currentStatus, TicketStatus.TRIAGED);
        }
        if (currentAssigneeId != null) {
            throw new TicketAlreadyAssignedException();
        }

        return new TicketAssigned(
            ticketId, currentStatus, TicketStatus.ASSIGNED, assigneeId, actorType, actorId, reason,
            currentVersion + 1, occurredAt
        );
    }

    /** SPEC-TW-008 §2/§4: replaces the assignee, preserves status. */
    public static TicketReassigned reassign(
        TicketId ticketId,
        TicketStatus currentStatus,
        String currentAssigneeId,
        long currentVersion,
        String newAssigneeId,
        String actorType,
        String actorId,
        String reason,
        Instant occurredAt
    ) {
        Objects.requireNonNull(ticketId, "ticketId must not be null");
        Objects.requireNonNull(currentStatus, "currentStatus must not be null");
        Objects.requireNonNull(newAssigneeId, "newAssigneeId must not be null");
        Objects.requireNonNull(actorType, "actorType must not be null");
        Objects.requireNonNull(actorId, "actorId must not be null");
        Objects.requireNonNull(reason, "reason must not be null");
        Objects.requireNonNull(occurredAt, "occurredAt must not be null");
        if (currentAssigneeId == null) {
            throw new TicketNotAssignedException();
        }
        if (!REASSIGNABLE_STATUSES.contains(currentStatus)) {
            throw new InvalidTicketStateException(currentStatus, REASSIGNABLE_STATUSES);
        }
        if (newAssigneeId.equals(currentAssigneeId)) {
            throw new ReassignmentRequiresDifferentAssigneeException();
        }

        return new TicketReassigned(
            ticketId, currentStatus, currentAssigneeId, newAssigneeId, actorType, actorId, reason,
            currentVersion + 1, occurredAt
        );
    }

    /** SPEC-TW-008 §2/§5: {@code ASSIGNED → TRIAGED}, assignee cleared. */
    public static TicketUnassigned unassign(
        TicketId ticketId,
        TicketStatus currentStatus,
        String currentAssigneeId,
        long currentVersion,
        String actorType,
        String actorId,
        String reason,
        Instant occurredAt
    ) {
        Objects.requireNonNull(ticketId, "ticketId must not be null");
        Objects.requireNonNull(currentStatus, "currentStatus must not be null");
        Objects.requireNonNull(actorType, "actorType must not be null");
        Objects.requireNonNull(actorId, "actorId must not be null");
        Objects.requireNonNull(reason, "reason must not be null");
        Objects.requireNonNull(occurredAt, "occurredAt must not be null");
        if (currentStatus != TicketStatus.ASSIGNED) {
            throw new InvalidTicketTransitionException(currentStatus, TicketStatus.ASSIGNED);
        }
        if (currentAssigneeId == null) {
            throw new TicketNotAssignedException();
        }

        return new TicketUnassigned(
            ticketId, currentStatus, TicketStatus.TRIAGED, currentAssigneeId, actorType, actorId, reason,
            currentVersion + 1, occurredAt
        );
    }

    public static TicketStatusChanged transitionStatus(
        TicketId ticketId,
        TicketStatus currentStatus,
        String currentAssigneeId,
        long currentVersion,
        TicketStatus targetStatus,
        String actorType,
        String actorId,
        String reason,
        Instant waitingForRequesterSince,
        String approvalReference,
        Instant occurredAt
    ) {
        Objects.requireNonNull(ticketId, "ticketId must not be null");
        Objects.requireNonNull(currentStatus, "currentStatus must not be null");
        Objects.requireNonNull(targetStatus, "targetStatus must not be null");
        Objects.requireNonNull(actorType, "actorType must not be null");
        Objects.requireNonNull(actorId, "actorId must not be null");
        Objects.requireNonNull(reason, "reason must not be null");
        Objects.requireNonNull(occurredAt, "occurredAt must not be null");

        if (currentAssigneeId == null) {
            throw new TicketNotAssignedException();
        }

        TransitionDescriptor descriptor = transitionDescriptor(currentStatus, targetStatus);
        Instant effectiveWaitingForRequesterSince = null;
        String effectiveApprovalReference = null;

        if (targetStatus == TicketStatus.WAITING_FOR_USER) {
            effectiveWaitingForRequesterSince = waitingForRequesterSince == null ? occurredAt : waitingForRequesterSince;
            if (approvalReference != null && !approvalReference.isBlank()) {
                throw new IllegalArgumentException("approvalReference is only allowed for WAITING_FOR_APPROVAL");
            }
        } else if (targetStatus == TicketStatus.WAITING_FOR_APPROVAL) {
            if (approvalReference == null || approvalReference.isBlank()) {
                throw new IllegalArgumentException("approvalReference is required for WAITING_FOR_APPROVAL");
            }
            effectiveApprovalReference = approvalReference;
            if (waitingForRequesterSince != null) {
                throw new IllegalArgumentException("waitingForRequesterSince is only allowed for WAITING_FOR_USER");
            }
        } else {
            if (waitingForRequesterSince != null || (approvalReference != null && !approvalReference.isBlank())) {
                throw new IllegalArgumentException("waiting metadata is only allowed for waiting statuses");
            }
        }

        return new TicketStatusChanged(
            ticketId, currentStatus, targetStatus, currentAssigneeId, actorType, actorId, reason,
            descriptor.transitionId(), descriptor.reasonCode(), effectiveWaitingForRequesterSince, effectiveApprovalReference,
            currentVersion + 1, occurredAt
        );
    }

    /** SPEC-TW-010 §2/§3: {@code IN_PROGRESS -> RESOLVED} (transitionId {@code SM-010}, reasonCode {@code TICKET_RESOLVED}). */
    public static TicketResolved resolve(
        TicketId ticketId,
        TicketStatus currentStatus,
        String currentAssigneeId,
        UUID currentResolutionCycleId,
        long currentVersion,
        ResolutionCode resolutionCode,
        String resolutionSummary,
        Instant autoCloseDueAt,
        String actorType,
        String actorId,
        Instant occurredAt
    ) {
        Objects.requireNonNull(ticketId, "ticketId must not be null");
        Objects.requireNonNull(currentStatus, "currentStatus must not be null");
        Objects.requireNonNull(currentResolutionCycleId, "currentResolutionCycleId must not be null");
        Objects.requireNonNull(resolutionCode, "resolutionCode must not be null");
        Objects.requireNonNull(resolutionSummary, "resolutionSummary must not be null");
        Objects.requireNonNull(autoCloseDueAt, "autoCloseDueAt must not be null");
        Objects.requireNonNull(actorType, "actorType must not be null");
        Objects.requireNonNull(actorId, "actorId must not be null");
        Objects.requireNonNull(occurredAt, "occurredAt must not be null");
        if (currentStatus != TicketStatus.IN_PROGRESS) {
            throw new InvalidStatusTransitionException(currentStatus, TicketStatus.RESOLVED);
        }
        if (currentAssigneeId == null) {
            throw new TicketNotAssignedException();
        }
        String trimmedSummary = resolutionSummary.trim();
        if (trimmedSummary.length() < 10 || trimmedSummary.length() > 5000) {
            throw new IllegalArgumentException("resolutionSummary must be 10 to 5000 characters once trimmed");
        }

        return new TicketResolved(
            ticketId, currentStatus, TicketStatus.RESOLVED, currentAssigneeId, currentResolutionCycleId,
            resolutionCode, trimmedSummary, actorType, actorId, occurredAt, autoCloseDueAt,
            "SM-010", "TICKET_RESOLVED", currentVersion + 1, occurredAt
        );
    }

    public static final Set<TicketStatus> REOPENABLE_STATUSES = Set.of(TicketStatus.RESOLVED, TicketStatus.CLOSED);

    /** SPEC-TW-011 §2: {@code RESOLVED -> CLOSED} (transitionId {@code SM-011}, reasonCode {@code TICKET_CLOSED}). */
    public static TicketClosed close(
        TicketId ticketId,
        TicketStatus currentStatus,
        String currentAssigneeId,
        UUID currentResolutionCycleId,
        long currentVersion,
        CloseReasonCode closeReasonCode,
        String closeReason,
        String actorType,
        String actorId,
        Instant occurredAt
    ) {
        Objects.requireNonNull(ticketId, "ticketId must not be null");
        Objects.requireNonNull(currentStatus, "currentStatus must not be null");
        Objects.requireNonNull(currentResolutionCycleId, "currentResolutionCycleId must not be null");
        Objects.requireNonNull(closeReasonCode, "closeReasonCode must not be null");
        Objects.requireNonNull(closeReason, "closeReason must not be null");
        Objects.requireNonNull(actorType, "actorType must not be null");
        Objects.requireNonNull(actorId, "actorId must not be null");
        Objects.requireNonNull(occurredAt, "occurredAt must not be null");
        if (currentStatus != TicketStatus.RESOLVED) {
            throw new InvalidStatusTransitionException(currentStatus, TicketStatus.CLOSED);
        }
        if (currentAssigneeId == null) {
            throw new TicketNotAssignedException();
        }
        String trimmedReason = closeReason.trim();
        if (trimmedReason.length() < 3 || trimmedReason.length() > 500) {
            throw new IllegalArgumentException("closeReason must be 3 to 500 characters once trimmed");
        }

        return new TicketClosed(
            ticketId, currentStatus, TicketStatus.CLOSED, currentAssigneeId, currentResolutionCycleId,
            closeReasonCode, trimmedReason, actorType, actorId, occurredAt,
            "SM-011", "TICKET_CLOSED", currentVersion + 1, occurredAt
        );
    }

    /**
     * SPEC-TW-011 §3: {@code RESOLVED -> IN_PROGRESS} (transitionId {@code
     * SM-012}) or {@code CLOSED -> IN_PROGRESS} (transitionId {@code
     * SM-013}). {@code ownershipStatus} is supplied by the caller (the
     * Application layer already resolved it via the Support Agent
     * Directory — an I/O lookup this pure method must not perform).
     */
    public static TicketReopened reopen(
        TicketId ticketId,
        TicketStatus currentStatus,
        String currentAssigneeId,
        UUID currentResolutionCycleId,
        int currentResolutionCycleNumber,
        UUID newResolutionCycleId,
        long currentVersion,
        int currentReopenCount,
        ReopenReasonCode reopenReasonCode,
        String reopenReason,
        OwnershipStatus ownershipStatus,
        String actorType,
        String actorId,
        Instant occurredAt
    ) {
        Objects.requireNonNull(ticketId, "ticketId must not be null");
        Objects.requireNonNull(currentStatus, "currentStatus must not be null");
        Objects.requireNonNull(currentResolutionCycleId, "currentResolutionCycleId must not be null");
        Objects.requireNonNull(newResolutionCycleId, "newResolutionCycleId must not be null");
        Objects.requireNonNull(reopenReasonCode, "reopenReasonCode must not be null");
        Objects.requireNonNull(reopenReason, "reopenReason must not be null");
        Objects.requireNonNull(ownershipStatus, "ownershipStatus must not be null");
        Objects.requireNonNull(actorType, "actorType must not be null");
        Objects.requireNonNull(actorId, "actorId must not be null");
        Objects.requireNonNull(occurredAt, "occurredAt must not be null");
        if (!REOPENABLE_STATUSES.contains(currentStatus)) {
            throw new InvalidTicketStateException(currentStatus, REOPENABLE_STATUSES);
        }
        String trimmedReason = reopenReason.trim();
        if (trimmedReason.length() < 10 || trimmedReason.length() > 1000) {
            throw new IllegalArgumentException("reopenReason must be 10 to 1000 characters once trimmed");
        }

        String transitionId = currentStatus == TicketStatus.RESOLVED ? "SM-012" : "SM-013";

        return new TicketReopened(
            ticketId, currentStatus, TicketStatus.IN_PROGRESS, currentAssigneeId, currentResolutionCycleId,
            newResolutionCycleId, currentResolutionCycleNumber + 1, reopenReasonCode, trimmedReason,
            actorType, actorId, occurredAt, currentReopenCount + 1, ownershipStatus,
            transitionId, "TICKET_REOPENED", currentVersion + 1, occurredAt
        );
    }

    /** SPEC-TW-012 §1: {@code IN_PROGRESS -> WAITING_FOR_USER} (transitionId {@code SM-014}, reasonCode {@code USER_INPUT_REQUIRED}). */
    public static TicketUserInputRequested requestUserInput(
        TicketId ticketId,
        TicketStatus currentStatus,
        String currentAssigneeId,
        long currentVersion,
        UUID requestId,
        String prompt,
        List<String> requestedFields,
        Instant expiresAt,
        String actorType,
        String actorId,
        Instant occurredAt
    ) {
        Objects.requireNonNull(ticketId, "ticketId must not be null");
        Objects.requireNonNull(currentStatus, "currentStatus must not be null");
        Objects.requireNonNull(requestId, "requestId must not be null");
        Objects.requireNonNull(prompt, "prompt must not be null");
        Objects.requireNonNull(actorType, "actorType must not be null");
        Objects.requireNonNull(actorId, "actorId must not be null");
        Objects.requireNonNull(occurredAt, "occurredAt must not be null");
        if (currentStatus != TicketStatus.IN_PROGRESS) {
            throw new InvalidStatusTransitionException(currentStatus, TicketStatus.WAITING_FOR_USER);
        }
        if (currentAssigneeId == null) {
            throw new TicketNotAssignedException();
        }
        String trimmedPrompt = prompt.trim();
        if (trimmedPrompt.length() < 10 || trimmedPrompt.length() > 2000) {
            throw new IllegalArgumentException("prompt must be 10 to 2000 characters once trimmed");
        }

        return new TicketUserInputRequested(
            ticketId, currentStatus, TicketStatus.WAITING_FOR_USER, currentAssigneeId, requestId, trimmedPrompt,
            requestedFields, actorType, actorId, occurredAt, occurredAt, "IN_PROGRESS", expiresAt,
            "SM-014", "USER_INPUT_REQUIRED", currentVersion + 1, occurredAt
        );
    }

    /**
     * SPEC-TW-013 §1: {@code WAITING_FOR_USER -> IN_PROGRESS} (transitionId
     * {@code SM-015}, reasonCode {@code USER_REPLIED}). The Application
     * layer only invokes this once it has already confirmed the reply
     * targets the ticket's current {@code OPEN} user input request — this
     * method's own status check is a defensive invariant, not the primary
     * gate (that decision needs the request row, which this pure method
     * must not query).
     */
    public static TicketUserInputResumed resumeFromUserReply(
        TicketId ticketId,
        TicketStatus currentStatus,
        long currentVersion,
        UUID requestId,
        UUID messageId,
        String actorType,
        String actorId,
        Instant occurredAt
    ) {
        Objects.requireNonNull(ticketId, "ticketId must not be null");
        Objects.requireNonNull(currentStatus, "currentStatus must not be null");
        Objects.requireNonNull(requestId, "requestId must not be null");
        Objects.requireNonNull(messageId, "messageId must not be null");
        Objects.requireNonNull(actorType, "actorType must not be null");
        Objects.requireNonNull(actorId, "actorId must not be null");
        Objects.requireNonNull(occurredAt, "occurredAt must not be null");
        if (currentStatus != TicketStatus.WAITING_FOR_USER) {
            throw new InvalidStatusTransitionException(currentStatus, TicketStatus.IN_PROGRESS);
        }

        return new TicketUserInputResumed(
            ticketId, currentStatus, TicketStatus.IN_PROGRESS, requestId, messageId, actorType, actorId, occurredAt,
            "SM-015", "USER_REPLIED", currentVersion + 1, occurredAt
        );
    }

    /**
     * SPEC-TW-014 domain-rules §1: {@code IN_PROGRESS -> WAITING_FOR_APPROVAL}
     * (transitionId {@code SM-016}, reasonCode {@code APPROVAL_REQUIRED}).
     * Distinct from the generic {@link #transitionStatus} path used by
     * SPEC-TW-009 (transitionId {@code SM-007} for the same status pair,
     * with a client-supplied {@code approvalReference}): this command binds
     * ticket, workflow, action, and a risk-context snapshot, and the
     * {@code approvalId} is generated by the Application layer rather than
     * accepted from the client, per domain-rules §1's "clients cannot spoof
     * approver or approval decision".
     */
    public static TicketApprovalWaitStarted requestApproval(
        TicketId ticketId,
        TicketStatus currentStatus,
        String currentAssigneeId,
        long currentVersion,
        UUID approvalRequestId,
        String approvalId,
        String workflowId,
        String actionId,
        String actionType,
        ApprovalRiskLevel riskLevel,
        Map<String, Object> riskContext,
        String reason,
        String actorType,
        String actorId,
        Instant occurredAt
    ) {
        Objects.requireNonNull(ticketId, "ticketId must not be null");
        Objects.requireNonNull(currentStatus, "currentStatus must not be null");
        Objects.requireNonNull(approvalRequestId, "approvalRequestId must not be null");
        Objects.requireNonNull(approvalId, "approvalId must not be null");
        Objects.requireNonNull(workflowId, "workflowId must not be null");
        Objects.requireNonNull(actionId, "actionId must not be null");
        Objects.requireNonNull(actionType, "actionType must not be null");
        Objects.requireNonNull(riskLevel, "riskLevel must not be null");
        Objects.requireNonNull(actorType, "actorType must not be null");
        Objects.requireNonNull(actorId, "actorId must not be null");
        Objects.requireNonNull(occurredAt, "occurredAt must not be null");
        if (currentStatus != TicketStatus.IN_PROGRESS) {
            throw new InvalidStatusTransitionException(currentStatus, TicketStatus.WAITING_FOR_APPROVAL);
        }
        if (currentAssigneeId == null) {
            throw new TicketNotAssignedException();
        }
        String trimmedWorkflowId = workflowId.trim();
        String trimmedActionId = actionId.trim();
        String trimmedActionType = actionType.trim();
        if (trimmedWorkflowId.isEmpty() || trimmedActionId.isEmpty() || trimmedActionType.isEmpty()) {
            throw new IllegalArgumentException("workflowId, actionId, and actionType must not be blank");
        }
        if (riskContext == null || riskContext.isEmpty()) {
            throw new IllegalArgumentException("riskContext must not be empty");
        }

        return new TicketApprovalWaitStarted(
            ticketId, currentStatus, TicketStatus.WAITING_FOR_APPROVAL, currentAssigneeId, approvalRequestId, approvalId,
            trimmedWorkflowId, trimmedActionId, trimmedActionType, riskLevel, riskContext,
            reason == null ? null : reason.trim(), actorType, actorId, occurredAt,
            "SM-016", "APPROVAL_REQUIRED", currentVersion + 1, occurredAt
        );
    }

    /**
     * SPEC-TW-015 domain-rules §1: {@code WAITING_FOR_APPROVAL -> IN_PROGRESS}
     * (transitionId {@code SM-017}, reasonCode {@code APPROVAL_GRANTED}).
     * Consumer-side classification (duplicate/stale/reference-mismatch/
     * expired-at-grant-time) happens in the Application layer, against the
     * approval-request row, before this method is ever invoked — this pure
     * method only re-asserts the ticket-level invariant that the Approval
     * Service's trust boundary already depends on: the ticket must still be
     * {@code WAITING_FOR_APPROVAL} when the grant is applied.
     */
    public static TicketApprovalGrantedApplied applyApprovalGranted(
        TicketId ticketId,
        TicketStatus currentStatus,
        String currentAssigneeId,
        long currentVersion,
        UUID approvalRequestId,
        String approvalId,
        String workflowId,
        String actionId,
        String actionType,
        String approvedBy,
        Instant approvedAt,
        String authorizationReference,
        String grantedEventId,
        Instant occurredAt
    ) {
        Objects.requireNonNull(ticketId, "ticketId must not be null");
        Objects.requireNonNull(currentStatus, "currentStatus must not be null");
        Objects.requireNonNull(approvalRequestId, "approvalRequestId must not be null");
        Objects.requireNonNull(approvalId, "approvalId must not be null");
        Objects.requireNonNull(workflowId, "workflowId must not be null");
        Objects.requireNonNull(actionId, "actionId must not be null");
        Objects.requireNonNull(actionType, "actionType must not be null");
        Objects.requireNonNull(approvedAt, "approvedAt must not be null");
        Objects.requireNonNull(authorizationReference, "authorizationReference must not be null");
        Objects.requireNonNull(grantedEventId, "grantedEventId must not be null");
        Objects.requireNonNull(occurredAt, "occurredAt must not be null");
        if (currentStatus != TicketStatus.WAITING_FOR_APPROVAL) {
            throw new InvalidStatusTransitionException(currentStatus, TicketStatus.IN_PROGRESS);
        }
        if (currentAssigneeId == null) {
            throw new TicketNotAssignedException();
        }

        return new TicketApprovalGrantedApplied(
            ticketId, currentStatus, TicketStatus.IN_PROGRESS, currentAssigneeId, approvalRequestId, approvalId,
            workflowId, actionId, actionType, approvedBy, approvedAt, authorizationReference, grantedEventId,
            "SM-017", "APPROVAL_GRANTED", currentVersion + 1, occurredAt
        );
    }

    private static TransitionDescriptor transitionDescriptor(TicketStatus currentStatus, TicketStatus targetStatus) {
        if (currentStatus == TicketStatus.ASSIGNED && targetStatus == TicketStatus.IN_PROGRESS) {
            return new TransitionDescriptor("SM-005", "WORK_STARTED");
        }
        if (currentStatus == TicketStatus.IN_PROGRESS && targetStatus == TicketStatus.WAITING_FOR_USER) {
            return new TransitionDescriptor("SM-006", "WAITING_FOR_USER");
        }
        if (currentStatus == TicketStatus.IN_PROGRESS && targetStatus == TicketStatus.WAITING_FOR_APPROVAL) {
            return new TransitionDescriptor("SM-007", "WAITING_FOR_APPROVAL");
        }
        if (currentStatus == TicketStatus.WAITING_FOR_USER && targetStatus == TicketStatus.IN_PROGRESS) {
            return new TransitionDescriptor("SM-008", "WORK_RESUMED");
        }
        if (currentStatus == TicketStatus.WAITING_FOR_APPROVAL && targetStatus == TicketStatus.IN_PROGRESS) {
            return new TransitionDescriptor("SM-009", "WORK_RESUMED");
        }
        throw new InvalidStatusTransitionException(currentStatus, targetStatus);
    }

    private record TransitionDescriptor(String transitionId, String reasonCode) {
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
